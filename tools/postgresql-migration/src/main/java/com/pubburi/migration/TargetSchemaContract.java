package com.pubburi.migration;

import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;

/** Verify the locked target catalog, not merely Flyway's historical SQL checksums. */
final class TargetSchemaContract {
    private TargetSchemaContract(){ }
    static void verify(Connection connection) throws Exception {
        Set<String> actual=new HashSet<>();
        try(var s=connection.createStatement();var r=s.executeQuery("SELECT table_name FROM information_schema.tables WHERE table_schema='pubburi' AND table_type='BASE TABLE'")) {while(r.next())actual.add(r.getString(1));}
        Set<String> expected=new HashSet<>(SourceContract.TABLES.stream().map(SourceContract.Table::name).toList());expected.add("flyway_schema_history");expected.add("migration_source_receipt");
        if(!actual.equals(expected)) throw new IllegalArgumentException("postgresql_import_schema_set_changed");
        if(!SchemaIndexContract.actual(connection).containsAll(SchemaIndexContract.expected())) throw new IllegalArgumentException("postgresql_import_indexes_changed");
        try(var s=connection.createStatement();var r=s.executeQuery("SELECT c.collprovider,c.collisdeterministic,c.colllocale FROM pg_collation c JOIN pg_namespace n ON n.oid=c.collnamespace WHERE n.nspname='pubburi' AND c.collname='pubburi_ci'")) {
            if(!r.next() || !r.getString(1).equals("i") || r.getBoolean(2) || !"und-u-ks-level1".equals(r.getString(3))) throw new IllegalArgumentException("postgresql_import_icu_changed");
        }
        for(var table:SourceContract.TABLES) {
            try(var s=connection.prepareStatement("SELECT column_name,data_type,is_nullable,character_maximum_length,numeric_precision,numeric_scale,datetime_precision,is_identity,identity_generation,collation_schema,collation_name FROM information_schema.columns WHERE table_schema='pubburi' AND table_name=? ORDER BY ordinal_position")) {
                s.setString(1,table.name());try(var r=s.executeQuery()) {
                    for(var column:table.columns()) {
                        if(!r.next() || !column.name().equals(r.getString(1)) || !dataType(table,column).equals(r.getString(2)) || column.nullable()!=r.getString(3).equals("YES")) throw new IllegalArgumentException("postgresql_import_column_changed");
                        if(column.kind().equals("TEXT") && r.getLong(4)!=Long.parseLong(column.limit())) throw new IllegalArgumentException("postgresql_import_column_length_changed");
                        if(column.kind().equals("DECIMAL") && (r.getInt(5)!=5 || r.getInt(6)!=2)) throw new IllegalArgumentException("postgresql_import_precision_changed");
                        if(column.kind().equals("INSTANT") && r.getInt(7)!=6) throw new IllegalArgumentException("postgresql_import_precision_changed");
                        if(column.kind().equals("TEXT")) {
                            String expectedCollation=collation(table.name(),column.name());
                            String actualCollation=r.getString(11)==null?"default":r.getString(10)+"."+r.getString(11);
                            if(!expectedCollation.equals(actualCollation)) throw new IllegalArgumentException("postgresql_import_collation_changed");
                        }
                        boolean identity=IdentityReservations.IDENTITIES.stream().anyMatch(i->i.table().equals(table.name()) && i.column().equals(column.name()));
                        if(identity!=r.getString(8).equals("YES") || (identity && !"BY DEFAULT".equals(r.getString(9)))) throw new IllegalArgumentException("postgresql_import_identity_changed");
                    }
                    if(r.next()) throw new IllegalArgumentException("postgresql_import_column_changed");
                }
            }
        }
        Map<String,String> primary=new HashMap<>();Set<String> foreign=new HashSet<>();
        String query="""
            SELECT t.relname,c.contype,c.convalidated,c.condeferrable,c.confdeltype,
              (SELECT string_agg(a.attname,',' ORDER BY k.n) FROM unnest(c.conkey) WITH ORDINALITY k(id,n) JOIN pg_attribute a ON a.attrelid=t.oid AND a.attnum=k.id) child,
              p.relname parent,
              (SELECT string_agg(a.attname,',' ORDER BY k.n) FROM unnest(c.confkey) WITH ORDINALITY k(id,n) JOIN pg_attribute a ON a.attrelid=p.oid AND a.attnum=k.id) parent_columns,pns.nspname parent_schema
            FROM pg_constraint c JOIN pg_class t ON t.oid=c.conrelid JOIN pg_namespace ns ON ns.oid=t.relnamespace
            LEFT JOIN pg_class p ON p.oid=c.confrelid LEFT JOIN pg_namespace pns ON pns.oid=p.relnamespace WHERE ns.nspname='pubburi'
            """;
        try(var s=connection.createStatement();var r=s.executeQuery(query)) {
            while(r.next()) {
                if(!r.getBoolean(3) || r.getBoolean(4)) throw new IllegalArgumentException("postgresql_import_constraint_changed");
                if(r.getString(2).equals("p")) primary.put(r.getString(1),r.getString(6));
                if(r.getString(2).equals("f")) {
                    if(!"pubburi".equals(r.getString(9))) throw new IllegalArgumentException("postgresql_import_foreign_namespace_changed");
                    String rule=switch(r.getString(5)){case "c"->"CASCADE";case "n"->"SET NULL";case "r"->"RESTRICT";default->"UNSUPPORTED";};
                    foreign.add(r.getString(1)+'\t'+r.getString(6)+'\t'+r.getString(7)+'\t'+r.getString(8)+'\t'+rule);
                }
            }
        }
        for(var table:SourceContract.TABLES) if(!String.join(",",table.primary()).equals(primary.get(table.name()))) throw new IllegalArgumentException("postgresql_import_primary_changed");
        for(var identity:IdentityReservations.IDENTITIES)sequence(connection,identity);
        if(!foreign.equals(Set.copyOf(new String(SourceContract.resource("source-foreign-contract.tsv"),StandardCharsets.UTF_8).lines().toList()))) throw new IllegalArgumentException("postgresql_import_foreign_changed");
    }
    static void sequence(Connection connection,IdentityReservations.Identity identity) throws SQLException {
        try(var s=connection.prepareStatement("SELECT pg_get_serial_sequence(?,?),seqincrement,seqmin,seqmax,seqcache,seqcycle FROM pg_sequence WHERE seqrelid=?::regclass")) {
            s.setString(1,"pubburi."+identity.table());s.setString(2,identity.column());s.setString(3,identity.sequence());
            try(var r=s.executeQuery()) {
                if(!r.next() || !identity.sequence().equals(r.getString(1)) || r.getLong(2)!=1 || r.getLong(3)!=1 || r.getLong(4)!=identity.maximum() || r.getLong(5)!=1 || r.getBoolean(6))throw new IllegalArgumentException("postgresql_import_sequence_changed");
            }
        }
    }
    static String collation(String table,String column) {
        String key=table+"."+column;
        if(Set.of("t_user.id","t_user.name","t_product.name","t_product.type","t_order.user_id","t_order_detail.product_name","t_order_detail.product_type",
                  "t_comment.user_id","t_stamp.user_id","t_market.name","t_user_preference.user_id","t_user_preference_value.user_id","t_user_favorite.user_id").contains(key)) return "pubburi.pubburi_ci";
        if(Set.of("t_product_feature_tag.tag","t_user_preference.survey_status","t_user_preference_value.kind","t_user_preference_value.value_text","t_product_search_relay_target.fingerprint").contains(key)) return "pg_catalog.C";
        return "default";
    }
    static String dataType(SourceContract.Table table,SourceContract.Column c) {
        return switch(c.kind()) {
            case "TEXT" -> c.sqlType().equals("char")?"character":"character varying";case "UUID"->"uuid";
            case "I32" -> table.name().equals("t_product_search_relay_target")?"smallint":"integer";
            case "I64" -> "bigint";case "F32"->"real";case "F64"->"double precision";case "BOOL"->"boolean";
            case "DECIMAL"->"numeric";case "INSTANT"->"timestamp with time zone";case "JSONRAW"->"json";
            default->throw new IllegalArgumentException("postgresql_import_type_rejected");
        };
    }
}
