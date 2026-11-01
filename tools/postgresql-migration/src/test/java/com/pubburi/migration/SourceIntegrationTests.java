package com.pubburi.migration;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Disposable copied source only. Never upload these test XMLs or artifacts: source rows are private. */
@EnabledIfEnvironmentVariable(named="PUBBURI_SOURCE_INTEGRATION",matches="1")
class SourceIntegrationTests {
    final Map<String,String> env=System.getenv();
    Path output(String purpose)throws Exception {
        Path parent=Path.of(env.get("PUBBURI_SOURCE_SNAPSHOT_DIR")).toAbsolutePath().getParent();Files.createDirectories(parent);
        return parent.resolve("worker-source-"+purpose+"-"+UUID.randomUUID());
    }
    Connection control()throws Exception {
        if(!"1".equals(env.get("PUBBURI_SOURCE_MUTATING")))throw new IllegalArgumentException("source_control_not_reserved");
        var settings=SourceSettings.from(env);var p=settings.properties();p.setProperty("user",SourceSettings.required(env,"PUBBURI_SOURCE_CONTROL_USER"));p.setProperty("password",SourceSettings.required(env,"PUBBURI_SOURCE_CONTROL_PASSWORD"));
        return DriverManager.getConnection(settings.url(),p);
    }
    static JsonNode artifact(Path path)throws Exception{return SourceContract.parse(Files.readAllBytes(path.resolve("snapshot.json")));}
    static JsonNode table(JsonNode artifact,String name){for(var t:artifact.path("tables"))if(t.path("name").asText().equals(name))return t;throw new IllegalArgumentException();}
    static void update(Connection c,String sql,String value)throws SQLException{try(var s=c.prepareStatement(sql)){s.setString(1,value);assertEquals(1,s.executeUpdate());}}
    @Test void copiedSourceReadOnlySnapshotAndAllFailureBoundaries()throws Exception {
        var settings=SourceSettings.from(env);Path accepted=output("accepted");
        try(SourceSession source=new SourceSession(settings)) {
            assertEquals(16,source.counts.size());assertThrows(SQLException.class,()->{try(var s=source.connection.createStatement()){s.executeUpdate("UPDATE t_product SET price=price WHERE id=1");}});
            assertEquals(16,SourceArtifact.export(source,accepted).tables());
        }
        String shadow=env.get("PUBBURI_SOURCE_SHADOW_DB");
        if(shadow!=null) {
            assertEquals(settings.database().replace("_","a"),shadow);
            try(Connection reader=settings.connect();var statement=reader.createStatement()) {
                assertThrows(SQLException.class,()->statement.executeQuery("SELECT value FROM `"+shadow+"`.access_canary"));
            }
            var wildcard=new HashMap<>(env);wildcard.put("PUBBURI_SOURCE_USER",env.get("PUBBURI_SOURCE_WILDCARD_USER"));
            // Deliberately authored pre-fix pattern grant really reaches the colliding DB.
            try(Connection broad=SourceSettings.from(wildcard).connect();var statement=broad.createStatement();var rows=statement.executeQuery("SELECT value FROM `"+shadow+"`.access_canary")) {assertTrue(rows.next());assertEquals(1,rows.getInt(1));}
            // Exporter refuses that role before opening a source snapshot.
            assertThrows(IllegalArgumentException.class,()->{try(var unused=new SourceSession(SourceSettings.from(wildcard))){}});
        }
        var baseline=artifact(accepted);assertEquals(16,SourceArtifact.validate(accepted).tables());assertEquals(16,SourceArtifact.validate(accepted).tables());
        assertThrows(IllegalArgumentException.class,()->SourceArtifact.path(accepted,false));
        assertTrue(table(baseline,"t_product").path("rows").size()>0,"copied catalog required");
        // The database-side marker is not an authorization token for a writer account.
        var writer=new HashMap<>(env);writer.put("PUBBURI_SOURCE_USER",env.get("PUBBURI_SOURCE_CONTROL_USER"));writer.put("PUBBURI_SOURCE_PASSWORD",env.get("PUBBURI_SOURCE_CONTROL_PASSWORD"));
        assertThrows(IllegalArgumentException.class,()->{try(var unused=new SourceSession(SourceSettings.from(writer))){}});
        try(Connection control=control()) {
            String product,user,oldProduct,oldUser;
            try(var s=control.createStatement();var r=s.executeQuery("SELECT id,name FROM t_product ORDER BY id LIMIT 1")){assertTrue(r.next());product=r.getString(1);oldProduct=r.getString(2);}
            try(var s=control.createStatement();var r=s.executeQuery("SELECT id,name FROM t_user ORDER BY id LIMIT 1")){assertTrue(r.next());user=r.getString(1);oldUser=r.getString(2);}
            // Source DML may continue in a copied-source experiment: all sixteen reads retain one RR snapshot.
            try(SourceSession snapshot=new SourceSession(settings)) {
                control.setAutoCommit(false);
                update(control,"UPDATE t_product SET name=? WHERE id="+Integer.parseInt(product),"Authored changed source name");
                try(var s=control.prepareStatement("UPDATE t_user SET name=? WHERE id=?")){s.setString(1,"Authored changed account name");s.setString(2,user);assertEquals(1,s.executeUpdate());}
                control.commit();control.setAutoCommit(true);
                Path coherent=output("coherent");SourceArtifact.export(snapshot,coherent);
                assertTrue(table(artifact(coherent),"t_product").path("rows").get(0).get(1).textValue().equals(oldProduct),"RR catalog value must predate concurrent commit");
                assertTrue(table(artifact(coherent),"t_user").path("rows").get(0).get(1).textValue().equals(oldUser),"RR account value must predate concurrent commit");
            } finally {
                control.setAutoCommit(true);update(control,"UPDATE t_product SET name=? WHERE id="+Integer.parseInt(product),oldProduct);
                try(var s=control.prepareStatement("UPDATE t_user SET name=? WHERE id=?")){s.setString(1,oldUser);s.setString(2,user);assertEquals(1,s.executeUpdate());}
            }
            // New connections must still see exactly the same declared maintenance window.
            Path changed=output("changed-window");
            try(SourceSession snapshot=new SourceSession(settings)) {
                try(var s=control.prepareStatement("UPDATE pubburi_source_snapshot_guard SET state='ACTIVE' WHERE token=?")){s.setString(1,settings.token());assertEquals(1,s.executeUpdate());}
                assertThrows(IllegalArgumentException.class,()->SourceArtifact.export(snapshot,changed));
                assertFalse(SourceContract.parse(Files.readAllBytes(changed.resolve("receipt.json"))).path("accepted").booleanValue());
            } finally {
                try(var s=control.prepareStatement("UPDATE pubburi_source_snapshot_guard SET state='QUIESCED' WHERE token=?")){s.setString(1,settings.token());assertEquals(1,s.executeUpdate());}
            }
            // PostgreSQL text cannot contain NUL: never silently strip/normalize it.
            try {
                update(control,"UPDATE t_product SET name=? WHERE id="+Integer.parseInt(product),"Authored\u0000NUL");Path invalid=output("nul");
                try(SourceSession snapshot=new SourceSession(settings)){assertThrows(IllegalArgumentException.class,()->SourceArtifact.export(snapshot,invalid));}
                assertFalse(SourceContract.parse(Files.readAllBytes(invalid.resolve("receipt.json"))).path("accepted").booleanValue());
            } finally {update(control,"UPDATE t_product SET name=? WHERE id="+Integer.parseInt(product),oldProduct);}
            // Add/drop only this authored nullable column, never alter/reset a source identity.
            boolean added=false;
            try {
                try(var s=control.createStatement()){s.execute("ALTER TABLE t_product ADD COLUMN ci_snapshot_extra INT NULL");added=true;}
                assertThrows(IllegalArgumentException.class,()->{try(var unused=new SourceSession(settings)){}});
            } finally {if(added)try(var s=control.createStatement()){s.execute("ALTER TABLE t_product DROP COLUMN ci_snapshot_extra");}}
            // AUTO_INCREMENT is NOT MVCC or rolled back: conservative refusal, counter is intentionally not reset.
            Path highwater=output("changed-highwater");
            try(SourceSession snapshot=new SourceSession(settings)) {
                control.setAutoCommit(false);
                try(var s=control.createStatement()){assertEquals(1,s.executeUpdate("INSERT INTO t_product(name,type,price,img,abv,order_count) VALUES ('Authored rolled-back source','맥주',1000,'authored.png',5.5,0)"));}
                control.rollback();control.setAutoCommit(true);
                assertThrows(IllegalArgumentException.class,()->SourceArtifact.export(snapshot,highwater));
                assertFalse(SourceContract.parse(Files.readAllBytes(highwater.resolve("receipt.json"))).path("accepted").booleanValue());
            } finally {if(!control.getAutoCommit()){control.rollback();control.setAutoCommit(true);}}
        }
        Path finalOutput=output("restored");try(SourceSession source=new SourceSession(settings)){SourceArtifact.export(source,finalOutput);}
        var restored=artifact(finalOutput);
        for(int i=0;i<16;i++)assertTrue(baseline.path("tables").get(i).path("sha256").equals(restored.path("tables").get(i).path("sha256")),"business rows must be restored exactly");
        assertFalse(baseline.path("sourceNext").equals(restored.path("sourceNext")),"rolled-back allocation must remain reserved");
    }
}
