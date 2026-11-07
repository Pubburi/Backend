package com.pubburi.migration;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

/** Private copied-source rows: never upload this test's XML or raw artifacts/logs. */
@EnabledIfEnvironmentVariable(named="PUBBURI_PG_IMPORT_INTEGRATION",matches="1")
class SourceImportIntegrationTests {
    @TempDir Path temporary;
    Map<String,String> env(String purpose) throws Exception {
        var env=new HashMap<>(System.getenv());String database=env.get("PUBBURI_PG_IMPORT_"+purpose+"_DB_NAME"),token=env.get("PUBBURI_PG_IMPORT_"+purpose+"_RUN_TOKEN");
        assertNotNull(database);assertNotNull(token);String url=env.get("PUBBURI_PG_URL");
        env.put("PUBBURI_PG_URL",url.substring(0,url.lastIndexOf('/')+1)+database);env.put("PUBBURI_PG_DB_NAME",database);env.put("PUBBURI_PG_RUN_TOKEN",token);
        env.put("PUBBURI_PG_MUTATING","1");env.put("PUBBURI_PG_IMPORT_WRITES_STOPPED","1");
        if(purpose.equals("TYPED") && env.containsKey("PUBBURI_SOURCE_TYPED_SNAPSHOT_DIR")) {
            env.put("PUBBURI_SOURCE_SNAPSHOT_DIR",env.get("PUBBURI_SOURCE_TYPED_SNAPSHOT_DIR"));
            for(String key:List.of("URL","DB_NAME","USER","PASSWORD","RUN_TOKEN","WINDOW_UUID"))env.put("PUBBURI_SOURCE_"+key,env.get("PUBBURI_SOURCE_TYPED_"+key));
        }
        pin(env,Path.of(env.get("PUBBURI_SOURCE_SNAPSHOT_DIR")));return env;
    }
    static void pin(Map<String,String> env,Path artifact)throws Exception {
        var source=SourceArtifact.load(artifact);env.put("PUBBURI_SOURCE_SNAPSHOT_DIR",artifact.toString());env.put("PUBBURI_PG_IMPORT_SHA256",source.sha256());
        env.put("PUBBURI_PG_IMPORT_SOURCE_ID",source.data().path("sourceId").textValue());env.put("PUBBURI_PG_IMPORT_WINDOW_UUID",source.data().path("window").textValue());
    }
    static long number(Connection c,String sql)throws SQLException{try(var s=c.createStatement();var r=s.executeQuery(sql)){assertTrue(r.next());return r.getLong(1);}}
    static Map<String,String> sequences(Connection c)throws SQLException {
        var values=new LinkedHashMap<String,String>();for(var i:IdentityReservations.IDENTITIES)try(var s=c.createStatement();var r=s.executeQuery("SELECT last_value,is_called FROM "+i.sequence())){r.next();values.put(i.table(),r.getLong(1)+":"+r.getBoolean(2));}return values;
    }
    static void empty(Connection c)throws SQLException {
        for(var t:SourceContract.TABLES)assertEquals(0,number(c,"SELECT count(*) FROM pubburi."+t.name()),"all business rows must roll back");
        assertEquals(0,number(c,"SELECT count(*) FROM pubburi.migration_source_receipt"));
    }
    static void schema(Map<String,String> env)throws Exception{assertEquals(4,PostgresSchemaTool.run("migrate",env));}
    @Test void realSourceCopiesEveryValueRecordsReceiptAndRejectsReplay() throws Exception {
        var env=env("FRESH");
        var legacy=PostgresSchemaTool.Settings.from(env);PostgresSchemaTool.guard(legacy);
        assertEquals(2,PostgresSchemaTool.configuration(legacy,PostgresSchemaTool.LOCATION).target("2").load().migrate().migrationsExecuted);
        var source=SourceImportTool.input(env);var settings=PostgresSchemaTool.Settings.from(env);
        assertTrue(source.report().rows()>0);
        // A pending V3 must stop v1 import before rows, identities or a receipt can change.
        try(Connection c=settings.connect()) {
            var before=sequences(c);
            assertThrows(org.flywaydb.core.api.exception.FlywayValidateException.class,()->SourceImportTool.run(env));
            empty(c);assertEquals(before,sequences(c));
        }
        assertEquals(2,PostgresSchemaTool.run("migrate",env));
        assertEquals(source.report(),SourceImportTool.run(env));assertEquals(source.report(),SourceImportTool.verify(env));
        try(Connection c=settings.connect();var sql=c.createStatement()) {
            int id;try(var rows=sql.executeQuery("SELECT id FROM pubburi.t_comment ORDER BY id LIMIT 1")){assertTrue(rows.next());id=rows.getInt(1);}
            sql.execute("""
                INSERT INTO pubburi.t_user_recommendation_feedback(user_id,product_id,feedback)
                SELECT u.id,p.id,'MATCHED' FROM pubburi.t_user u CROSS JOIN pubburi.t_product p LIMIT 1
                """);
            try {
                var error=assertThrows(IllegalArgumentException.class,()->SourceImportTool.verify(env));
                assertEquals("postgresql_import_feedback_not_empty",error.getMessage());
                assertThrows(IllegalArgumentException.class,()->SourceImportTool.verifyCutover(env));
            } finally {sql.execute("DELETE FROM pubburi.t_user_recommendation_feedback");}
            sql.execute("INSERT INTO pubburi.t_comment_taste_tag VALUES("+id+",'authored target opinion')");
            try {var error=assertThrows(IllegalArgumentException.class,()->SourceImportTool.verify(env));assertEquals("postgresql_v1_review_tags_unsupported",error.getMessage());}
            finally {sql.execute("DELETE FROM pubburi.t_comment_taste_tag WHERE comment_id="+id);}
            sql.execute("ALTER TABLE pubburi.t_comment_taste_tag ALTER COLUMN tag TYPE VARCHAR(41) COLLATE \"C\"");
            try {var error=assertThrows(IllegalArgumentException.class,()->SourceImportTool.verify(env));assertEquals("postgresql_review_tag_shape_changed",error.getMessage());}
            finally {sql.execute("ALTER TABLE pubburi.t_comment_taste_tag ALTER COLUMN tag TYPE VARCHAR(40) COLLATE \"C\"");}
        }
        assertEquals(source.report(),SourceImportTool.verify(env));
        assertEquals(source.report(),SourceImportTool.verifyCutover(env));
        // Baseline verify-import only observes the old artifact and target, not current MySQL.
        // A committed same-row-count UPDATE must stop initial selection without touching either DB.
        try(Connection mysql=new SourceIntegrationTests().control();var sql=mysql.createStatement()) {
            int id;String name;
            try(var r=sql.executeQuery("SELECT id,name FROM t_product ORDER BY id LIMIT 1")){assertTrue(r.next());id=r.getInt(1);name=r.getString(2);}
            SourceIntegrationTests.update(mysql,"UPDATE t_product SET name=? WHERE id="+id,"Authored stale cutover source");
            try {
                assertEquals(source.report(),SourceImportTool.verify(env));
                var error=assertThrows(IllegalArgumentException.class,()->SourceImportTool.verifyCutover(env));
                assertEquals("source_cutover_value_changed",error.getMessage());
            } finally {SourceIntegrationTests.update(mysql,"UPDATE t_product SET name=? WHERE id="+id,name);}
            try(var s=mysql.prepareStatement("UPDATE pubburi_source_snapshot_guard SET state='ACTIVE' WHERE token=?")) {
                s.setString(1,env.get("PUBBURI_SOURCE_RUN_TOKEN"));assertEquals(1,s.executeUpdate());
            }
            try {assertThrows(IllegalArgumentException.class,()->SourceImportTool.verifyCutover(env));}
            finally {try(var s=mysql.prepareStatement("UPDATE pubburi_source_snapshot_guard SET state='QUIESCED' WHERE token=?")){s.setString(1,env.get("PUBBURI_SOURCE_RUN_TOKEN"));assertEquals(1,s.executeUpdate());}}
        }
        assertEquals(source.report(),SourceImportTool.verifyCutover(env));
        try(Connection c=settings.connect()) {
            SourceImportTool.compare(c,source);assertEquals(1,number(c,"SELECT count(*) FROM pubburi.migration_source_receipt"));
            for(var i:IdentityReservations.IDENTITIES)assertEquals(source.data().path("sourceNext").path(i.table()).textValue()+":false",sequences(c).get(i.table()));
            var before=sequences(c);assertThrows(IllegalArgumentException.class,()->SourceImportTool.run(env));SourceImportTool.compare(c,source);assertEquals(before,sequences(c));
            try(var s=c.createStatement();var r=s.executeQuery("SELECT artifact_sha256,source_id::text,source_server::text,window_id::text,row_count FROM pubburi.migration_source_receipt")) {
                assertTrue(r.next());assertTrue(source.sha256().equals(r.getString(1)),"approved digest must be persisted");
                assertTrue(source.data().path("sourceId").textValue().equals(r.getString(2)),"source UUID must be unchanged");
                assertTrue(source.data().path("sourceServer").textValue().equals(r.getString(3)),"server identity must be unchanged");
                assertTrue(source.data().path("window").textValue().equals(r.getString(4)),"window must be unchanged");assertEquals(source.report().rows(),r.getLong(5));
            }
        }
    }
    static void failure(Connection c,String table,String name,boolean middle)throws SQLException {
        try(var s=c.createStatement()) {
            s.execute("CREATE FUNCTION pubburi."+name+"() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "+(middle?"IF (SELECT count(*) FROM pubburi.t_product)>0 THEN ":"")+"RAISE EXCEPTION 'authored import failure'; "+(middle?"END IF; RETURN NEW; ":"")+"END $$");
            s.execute("CREATE TRIGGER "+name+" BEFORE INSERT ON pubburi."+table+" FOR EACH ROW EXECUTE FUNCTION pubburi."+name+"()");
        }
    }
    static void removeFailure(Connection c,String table,String name)throws SQLException{try(var s=c.createStatement()){s.execute("DROP TRIGGER "+name+" ON pubburi."+table);s.execute("DROP FUNCTION pubburi."+name+"()");}}
    @Test void earlyAndAfterAllSequencesFailuresRollbackThenSameArtifactCanRecoverThroughCli() throws Exception {
        var env=env("RECOVERY");schema(env);var settings=PostgresSchemaTool.Settings.from(env);var source=SourceImportTool.input(env);
        try(Connection c=settings.connect()) {
            var before=sequences(c);
            failure(c,"t_product","ci_import_product_failure",true);
            try {assertThrows(SQLException.class,()->SourceImportTool.run(env));empty(c);assertEquals(before,sequences(c));}
            finally {removeFailure(c,"t_product","ci_import_product_failure");}
            failure(c,"migration_source_receipt","ci_import_receipt_failure",false);
            try {assertThrows(SQLException.class,()->SourceImportTool.run(env));empty(c);assertEquals(before,sequences(c));}
            finally {removeFailure(c,"migration_source_receipt","ci_import_receipt_failure");}
        }
        Path root=Path.of(System.getProperty("user.dir")).resolve("../..").normalize();Path log=temporary.resolve("cli.log");
        ProcessBuilder process=new ProcessBuilder("python3","scripts/postgresql_import.py","import-source");process.directory(root.toFile());process.environment().putAll(env);process.redirectErrorStream(true);process.redirectOutput(log.toFile());
        Process child=process.start();assertTrue(child.waitFor(120,java.util.concurrent.TimeUnit.SECONDS),"CLI must terminate");assertEquals(0,child.exitValue());
        try(Connection c=settings.connect()){SourceImportTool.compare(c,source);assertEquals(1,number(c,"SELECT count(*) FROM pubburi.migration_source_receipt"));}
    }
    Path authored(boolean foreign) throws Exception {
        var helper=new SourceContractTests();helper.temporary=temporary;ObjectNode data=helper.fixture();
        for(var node:data.path("tables")) {
            ObjectNode table=(ObjectNode)node;ArrayNode rows=(ArrayNode)table.path("rows");
            if(table.path("name").asText().equals("t_user") && !foreign) {
                for(String id:List.of("AuthoredCase","authoredcase")){var row=rows.addArray();row.add(id).add("Authored").add("not-a-login-hash").add("USER").add("00000000-0000-4000-8000-000000000001").add("0");}
            }
            if(table.path("name").asText().equals("t_order") && foreign) {
                var row=rows.addArray();row.add("1").add("missing-author").add("DEMO").addNull().addNull().add("N").add("15000").add("SNAPSHOT");
                ((ObjectNode)data.path("sourceNext")).put("t_order","2");
            }
            var bytes=new java.io.ByteArrayOutputStream();for(var row:rows){bytes.write(SourceContract.JSON.writeValueAsBytes(row));bytes.write('\n');}
            table.put("count",rows.size());table.put("sha256",SourceContract.hash(bytes.toByteArray()));
        }
        int count=0;for(var table:data.path("tables"))count+=table.path("count").asInt();data.put("count",count);
        Path output=helper.save(data,foreign?"authored-foreign":"authored-collation");byte[] bytes=Files.readAllBytes(output.resolve("snapshot.json"));
        SourceArtifact.receipt(output,Map.of("schemaVersion",1,"accepted",true,"bytes",bytes.length,"sha256",SourceContract.hash(bytes),"tables",16,"rows",count));return output;
    }
    @Test void conflictingTypedArtifactsAndNonemptyTargetsNeverSilentlyMergeOrNormalize() throws Exception {
        var env=env("CONFLICT");schema(env);var settings=PostgresSchemaTool.Settings.from(env);
        try(Connection c=settings.connect()) {
            var before=sequences(c);
            try(var s=c.createStatement()) {
                s.execute("ALTER TABLE pubburi.t_product ALTER COLUMN name TYPE varchar(100) COLLATE \"C\"");
                try {assertThrows(IllegalArgumentException.class,()->SourceImportTool.run(env));empty(c);assertEquals(before,sequences(c));}
                finally {s.execute("ALTER TABLE pubburi.t_product ALTER COLUMN name TYPE varchar(100) COLLATE pubburi.pubburi_ci");}
                s.execute("CREATE SCHEMA ci_import_shadow");s.execute("CREATE TABLE ci_import_shadow.t_product(id INT PRIMARY KEY)");
                s.execute("ALTER TABLE pubburi.t_product_feature DROP CONSTRAINT fk_product_feature_product");
                s.execute("ALTER TABLE pubburi.t_product_feature ADD CONSTRAINT fk_product_feature_product FOREIGN KEY(product_id) REFERENCES ci_import_shadow.t_product(id) ON DELETE CASCADE");
                try {assertThrows(IllegalArgumentException.class,()->SourceImportTool.run(env));empty(c);assertEquals(before,sequences(c));}
                finally {
                    s.execute("ALTER TABLE pubburi.t_product_feature DROP CONSTRAINT fk_product_feature_product");
                    s.execute("ALTER TABLE pubburi.t_product_feature ADD CONSTRAINT fk_product_feature_product FOREIGN KEY(product_id) REFERENCES pubburi.t_product(id) ON DELETE CASCADE");
                    s.execute("DROP TABLE ci_import_shadow.t_product");s.execute("DROP SCHEMA ci_import_shadow");
                }
            }
            for(boolean foreign:List.of(false,true)) {
                var candidate=new HashMap<>(env);pin(candidate,authored(foreign));SQLException error=assertThrows(SQLException.class,()->SourceImportTool.run(candidate));
                assertEquals(foreign?"23503":"23505",error.getSQLState());empty(c);assertEquals(before,sequences(c));
            }
            try(var s=c.createStatement()){s.execute("INSERT INTO pubburi.t_product(id,name,type,price,img,abv) VALUES(-100,'Authored existing target','맥주',1000,'authored.png',5.5)");}
            assertThrows(IllegalArgumentException.class,()->SourceImportTool.run(env));
            assertEquals(1,number(c,"SELECT count(*) FROM pubburi.t_product WHERE id=-100 AND price=1000"));assertEquals(before,sequences(c));
        }
    }
}
