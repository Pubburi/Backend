package com.pubburi.migration;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;

/** Explicit stopped-writer copy into a guarded empty rehearsal target; never merge or reset data. */
public final class SourceImportTool {
    private SourceImportTool(){ }
    static String required(Map<String,String> env,String key){return SourceSettings.required(env,key);}
    static SourceArtifact.Snapshot input(Map<String,String> env) throws Exception {return input(env,true);}
    static SourceArtifact.Snapshot input(Map<String,String> env,boolean mutating) throws Exception {
        if((mutating && !"1".equals(env.get("PUBBURI_PG_MUTATING"))) || !"1".equals(env.get("PUBBURI_PG_IMPORT_WRITES_STOPPED"))) throw new IllegalArgumentException("postgresql_import_explicit_window_required");
        var source=SourceArtifact.load(Path.of(required(env,"PUBBURI_SOURCE_SNAPSHOT_DIR")));
        if(!source.sha256().equals(required(env,"PUBBURI_PG_IMPORT_SHA256")) || !source.data().path("sourceId").textValue().equals(required(env,"PUBBURI_PG_IMPORT_SOURCE_ID"))
                || !source.data().path("window").textValue().equals(required(env,"PUBBURI_PG_IMPORT_WINDOW_UUID"))) throw new IllegalArgumentException("postgresql_import_input_binding_rejected");
        return source;
    }
    static String columns(SourceContract.Table table){return String.join(",",table.columns().stream().map(c->'"'+c.name()+'"').toList());}
    static void insert(Connection connection,SourceContract.Table spec,JsonNode table) throws SQLException {
        String placeholders=String.join(",",Collections.nCopies(spec.columns().size(),"?"));
        try(var statement=connection.prepareStatement("INSERT INTO pubburi."+spec.name()+" ("+columns(spec)+") VALUES ("+placeholders+")")) {
            for(JsonNode row:table.path("rows")) {
                for(int i=0;i<spec.columns().size();i++) TargetValues.bind(statement,i+1,spec.columns().get(i),row.get(i));
                if(statement.executeUpdate()!=1)throw new IllegalArgumentException("postgresql_import_insert_incomplete");
            }
        }
    }
    static void compare(Connection connection,SourceArtifact.Snapshot source) throws Exception {
        for(int t=0;t<SourceContract.TABLES.size();t++) {
            var spec=SourceContract.TABLES.get(t);var table=source.data().path("tables").get(t);
            Map<String,JsonNode> expected=new HashMap<>();for(var row:table.path("rows")) expected.put(TargetValues.key(spec,row),row);
            // Compare exact PK tuples, independently of MySQL versus ICU ordering/collation differences.
            try(var s=connection.createStatement();var rows=s.executeQuery("SELECT "+columns(spec)+" FROM pubburi."+spec.name())) {
                while(rows.next()) {
                    JsonNode actual=SourceContract.JSON.valueToTree(TargetValues.row(spec,rows));JsonNode wanted=expected.remove(TargetValues.key(spec,actual));
                    if(wanted==null || !wanted.equals(actual))throw new IllegalArgumentException("postgresql_import_value_mismatch");
                }
            }
            if(!expected.isEmpty())throw new IllegalArgumentException("postgresql_import_rows_missing");
        }
    }
    static void reserve(Connection connection,JsonNode sourceNext) throws SQLException {
        try(var statement=connection.createStatement()) {
            for(var identity:IdentityReservations.IDENTITIES) {
                TargetSchemaContract.sequence(connection,identity);
                // No-op metadata ALTER takes the sequence lock before reading last_value.
                // A bare nextval does not honor business-table locks.
                statement.execute("ALTER SEQUENCE "+identity.sequence()+" CACHE 1");
                TargetSchemaContract.sequence(connection,identity);
                long requested=Long.parseLong(sourceNext.path(identity.table()).textValue());
                try(var r=statement.executeQuery("SELECT last_value,is_called FROM "+identity.sequence())) {
                    r.next();long current=r.getLong(1);if(r.getBoolean(2))current=Math.addExact(current,1);
                    if(current>requested)throw new IllegalArgumentException("postgresql_import_sequence_would_move_backwards");
                }
            }
            for(var identity:IdentityReservations.IDENTITIES)statement.execute("ALTER SEQUENCE "+identity.sequence()+" RESTART WITH "+Long.parseLong(sourceNext.path(identity.table()).textValue()));
        }
    }
    static SourceArtifact.Report run(Map<String,String> env) throws Exception {
        // Read/validate once. No second file read after approved digest/identity checks.
        var source=input(env);var settings=PostgresSchemaTool.Settings.from(env);
        PostgresSchemaTool.guard(settings);PostgresSchemaTool.configuration(settings,PostgresSchemaTool.LOCATION).load().validate();
        return ImportTransaction.run(settings.connect(),connection -> {
                PostgresSchemaTool.guard(connection,settings);
                try(var marker=connection.prepareStatement("SELECT token FROM public.pubburi_pg_migration_guard WHERE token=?")) {
                    marker.setString(1,settings.token());try(var r=marker.executeQuery()){if(!r.next())throw new IllegalArgumentException("postgresql_import_guard_changed");}
                }
                try(var statement=connection.createStatement()) {
                    String names=String.join(",",SourceContract.TABLES.stream().map(t->"pubburi."+t.name()).toList());
                    statement.execute("LOCK TABLE "+names+",pubburi.migration_source_receipt IN ACCESS EXCLUSIVE MODE");
                    TargetSchemaContract.verify(connection);
                    for(var table:SourceContract.TABLES)try(var rows=statement.executeQuery("SELECT EXISTS(SELECT 1 FROM pubburi."+table.name()+")")) {
                        rows.next();if(rows.getBoolean(1))throw new IllegalArgumentException("postgresql_import_empty_target_required");
                    }
                    try(var rows=statement.executeQuery("SELECT EXISTS(SELECT 1 FROM pubburi.migration_source_receipt)")) {rows.next();if(rows.getBoolean(1))throw new IllegalArgumentException("postgresql_import_already_recorded");}
                }
                for(int t=0;t<SourceContract.TABLES.size();t++) insert(connection,SourceContract.TABLES.get(t),source.data().path("tables").get(t));
                compare(connection,source);reserve(connection,source.data().path("sourceNext"));
                try(var receipt=connection.prepareStatement("INSERT INTO pubburi.migration_source_receipt(singleton_id,artifact_sha256,source_id,source_server,window_id,row_count) VALUES(1,?,?::uuid,?::uuid,?::uuid,?)")) {
                    receipt.setString(1,source.sha256());receipt.setString(2,source.data().path("sourceId").textValue());receipt.setString(3,source.data().path("sourceServer").textValue());
                    receipt.setString(4,source.data().path("window").textValue());receipt.setLong(5,source.report().rows());if(receipt.executeUpdate()!=1)throw new IllegalArgumentException("postgresql_import_receipt_failed");
                }
                PostgresSchemaTool.guard(connection,settings);return source.report();
        });
    }
    static SourceArtifact.Report verify(Map<String,String> env) throws Exception {
        var source=input(env,false);var settings=PostgresSchemaTool.Settings.from(env);
        PostgresSchemaTool.guard(settings);PostgresSchemaTool.configuration(settings,PostgresSchemaTool.LOCATION).load().validate();
        try(Connection c=settings.connect()) {
            c.setReadOnly(true);c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);c.setAutoCommit(false);
            try {
                PostgresSchemaTool.guard(c,settings);
                try(var locks=c.createStatement()) {locks.execute("LOCK TABLE "+String.join(",",SourceContract.TABLES.stream().map(t->"pubburi."+t.name()).toList())+",pubburi.migration_source_receipt IN ACCESS SHARE MODE");}
                TargetSchemaContract.verify(c);
                try(var statement=c.createStatement();var r=statement.executeQuery("SELECT artifact_sha256,source_id::text,source_server::text,window_id::text,row_count FROM pubburi.migration_source_receipt WHERE singleton_id=1")) {
                    if(!r.next() || !source.sha256().equals(r.getString(1)) || !source.data().path("sourceId").textValue().equals(r.getString(2))
                            || !source.data().path("sourceServer").textValue().equals(r.getString(3)) || !source.data().path("window").textValue().equals(r.getString(4)) || source.report().rows()!=r.getLong(5))throw new IllegalArgumentException("postgresql_import_receipt_mismatch");
                }
                compare(c,source);
                for(var i:IdentityReservations.IDENTITIES) try(var s=c.createStatement();var r=s.executeQuery("SELECT last_value,is_called FROM "+i.sequence())) {
                    if(!r.next() || r.getBoolean(2) || r.getLong(1)!=Long.parseLong(source.data().path("sourceNext").path(i.table()).textValue()))throw new IllegalArgumentException("postgresql_import_reservation_changed");
                }
                c.commit();return source.report();
            } catch(Exception error){c.rollback();throw error;}
        }
    }
    public static int mainResult(String[] args,Map<String,String> env) {
        try {
            if(args.length!=1 || !Set.of("import-source","verify-import").contains(args[0]))throw new IllegalArgumentException();
            var report=args[0].equals("import-source")?run(env):verify(env);System.out.println("{\"status\":\"passed\",\"tables\":"+report.tables()+",\"rows\":"+report.rows()+"}");return 0;
        } catch(Exception error){
            String code=error instanceof ImportTransaction.OutcomeException outcome?outcome.code:"postgresql_source_import_rejected";
            System.err.println("{\"status\":\"failed\",\"error_code\":\""+code+"\"}");return 2;
        }
    }
    public static void main(String[] args){System.exit(mainResult(args,System.getenv()));}
}
