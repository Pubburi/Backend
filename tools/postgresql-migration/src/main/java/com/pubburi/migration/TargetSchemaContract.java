package com.pubburi.migration;

import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;

/** Verify the locked target catalog, not merely Flyway's historical SQL checksums. */
final class TargetSchemaContract {
    private TargetSchemaContract(){ }
    static void verify(Connection connection,int version) throws Exception {
        var contract=SourceContract.tables(version);
        Set<String> actual=new HashSet<>();
        try(var s=connection.createStatement();var r=s.executeQuery("SELECT table_name FROM information_schema.tables WHERE table_schema='pubburi' AND table_type='BASE TABLE'")) {while(r.next())actual.add(r.getString(1));}
        Set<String> expected=new HashSet<>(contract.stream().map(SourceContract.Table::name).toList());expected.add("flyway_schema_history");expected.add("migration_source_receipt");
        boolean reviewTags;
        // pg_class is not filtered by table SELECT grants; a hidden extension is never treated as absent.
        try(var s=connection.createStatement();var r=s.executeQuery("SELECT EXISTS(SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='pubburi' AND c.relname='t_comment_taste_tag')")) {r.next();reviewTags=r.getBoolean(1);}
        expected.add("t_user_recommendation_feedback");
        verifyEmptyFeedback(connection);
        expected.add("t_user_notebook");
        verifyEmptyNotebook(connection);
        expected.addAll(List.of("t_user_cart","t_user_cart_item","t_user_cart_operation"));
        verifyEmptyCart(connection);
        if(reviewTags && !actual.contains("t_comment_taste_tag")) throw new IllegalArgumentException("postgresql_review_tag_unreadable");
        if(version==2 && !reviewTags) throw new IllegalArgumentException("postgresql_v2_review_tags_required");
        if(reviewTags) {expected.add("t_comment_taste_tag");reviewTags(connection,version==1);}
        if(!actual.equals(expected)) throw new IllegalArgumentException("postgresql_import_schema_set_changed");
        var indexes=SchemaIndexContract.actual(connection);
        if(!indexes.containsAll(SchemaIndexContract.expected())
                || !indexes.contains(new SchemaIndexContract.Key("t_user_recommendation_feedback",false,"product_id:A:-"))
                || !indexes.contains(new SchemaIndexContract.Key("t_user_notebook",true,"user_id:A:-,product_id:A:L"))
                || !indexes.contains(new SchemaIndexContract.Key("t_user_notebook",false,"product_id:A:L"))
                || !indexes.contains(new SchemaIndexContract.Key("t_user_cart_item",false,"current_product_id:A:L"))) {
            throw new IllegalArgumentException("postgresql_import_indexes_changed");
        }
        try(var s=connection.createStatement();var r=s.executeQuery("SELECT c.collprovider,c.collisdeterministic,c.colllocale FROM pg_collation c JOIN pg_namespace n ON n.oid=c.collnamespace WHERE n.nspname='pubburi' AND c.collname='pubburi_ci'")) {
            if(!r.next() || !r.getString(1).equals("i") || r.getBoolean(2) || !"und-u-ks-level1".equals(r.getString(3))) throw new IllegalArgumentException("postgresql_import_icu_changed");
        }
        for(var table:contract) {
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
        for(var table:contract) if(!String.join(",",table.primary()).equals(primary.get(table.name()))) throw new IllegalArgumentException("postgresql_import_primary_changed");
        for(var identity:IdentityReservations.IDENTITIES)sequence(connection,identity);
        var expectedForeign=new HashSet<>(new String(SourceContract.resource("source-foreign-contract.tsv"),StandardCharsets.UTF_8).lines().toList());
        if(reviewTags) {
            if(!"comment_id,tag".equals(primary.get("t_comment_taste_tag"))) throw new IllegalArgumentException("postgresql_review_tag_shape_changed");
            expectedForeign.add(SourceContract.REVIEW_FOREIGN);
        }
        if(!"user_id,product_id".equals(primary.get("t_user_recommendation_feedback"))) throw new IllegalArgumentException("postgresql_feedback_primary_changed");
        expectedForeign.add("t_user_recommendation_feedback\tuser_id\tt_user\tid\tCASCADE");
        expectedForeign.add("t_user_recommendation_feedback\tproduct_id\tt_product\tid\tCASCADE");
        if(!"id".equals(primary.get("t_user_notebook")))throw new IllegalArgumentException("postgresql_notebook_primary_changed");
        expectedForeign.add("t_user_notebook\tuser_id\tt_user\tid\tCASCADE");
        expectedForeign.add("t_user_notebook\tproduct_id\tt_product\tid\tSET NULL");
        if(!"user_id".equals(primary.get("t_user_cart")) || !"user_id,product_id".equals(primary.get("t_user_cart_item"))
                || !"user_id,operation_id".equals(primary.get("t_user_cart_operation")))throw new IllegalArgumentException("postgresql_cart_primary_changed");
        expectedForeign.add("t_user_cart\tuser_id\tt_user\tid\tCASCADE");
        expectedForeign.add("t_user_cart_item\tuser_id\tt_user_cart\tuser_id\tCASCADE");
        expectedForeign.add("t_user_cart_item\tcurrent_product_id\tt_product\tid\tSET NULL");
        expectedForeign.add("t_user_cart_operation\tuser_id\tt_user_cart\tuser_id\tCASCADE");
        if(!foreign.equals(expectedForeign)) throw new IllegalArgumentException("postgresql_import_foreign_changed");
    }
    private static void verifyEmptyCart(Connection connection)throws SQLException{
        var shapes=Map.of(
            "t_user_cart","user_id:character varying(100):0:false,version:bigint:0:false,updated_at:timestamp(6) with time zone:0:false",
            "t_user_cart_item","user_id:character varying(100):0:false,product_id:integer:0:false,current_product_id:integer:0:true,product_name:character varying(100):0:false,quantity:integer:0:false",
            "t_user_cart_operation","user_id:character varying(100):0:false,operation_id:uuid:0:false,kind:character varying(8):0:false,status:character varying(7):0:false,intent_hash:character(64):0:false,order_id:integer:0:true");
        for(String table:List.of("t_user_cart","t_user_cart_item","t_user_cart_operation")){
            try(var s=connection.createStatement()){
                s.execute("LOCK TABLE pubburi."+table+" IN ACCESS SHARE MODE");
                try(var rows=s.executeQuery("SELECT EXISTS(SELECT 1 FROM pubburi."+table+")")){if(!rows.next() || rows.getBoolean(1))throw new IllegalArgumentException("postgresql_import_cart_not_empty");}
            }
            try(var s=connection.prepareStatement("SELECT string_agg(a.attname||':'||format_type(a.atttypid,a.atttypmod)||':'||a.attndims::text||':'||(NOT a.attnotnull)::text,',' ORDER BY a.attnum) FROM pg_attribute a JOIN pg_class c ON c.oid=a.attrelid JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='pubburi' AND c.relname=? AND a.attnum>0 AND NOT a.attisdropped")){
                s.setString(1,table);try(var rows=s.executeQuery()){if(!rows.next() || !shapes.get(table).equals(rows.getString(1)))throw new IllegalArgumentException("postgresql_cart_shape_changed");}
            }
        }
    }
    private static void verifyEmptyNotebook(Connection connection)throws SQLException{
        try(var s=connection.createStatement()){
            s.execute("LOCK TABLE pubburi.t_user_notebook IN ACCESS SHARE MODE");
            try(var rows=s.executeQuery("SELECT EXISTS(SELECT 1 FROM pubburi.t_user_notebook)")){
                if(!rows.next() || rows.getBoolean(1))throw new IllegalArgumentException("postgresql_import_notebook_not_empty");
            }
            String shape="id:uuid:0:false,user_id:character varying(100):0:false,product_id:integer:0:true,product_name:character varying(100):0:false,experience:character varying(10):0:false,rating:numeric(3,2):0:true,taste_tags:character varying(40)[]:1:true,aroma_tags:character varying(40)[]:1:true,memo:character varying(2000):0:true,version:bigint:0:false,created_at:timestamp(6) with time zone:0:false,updated_at:timestamp(6) with time zone:0:false";
            try(var rows=s.executeQuery("SELECT string_agg(a.attname||':'||format_type(a.atttypid,a.atttypmod)||':'||a.attndims::text||':'||(NOT a.attnotnull)::text,',' ORDER BY a.attnum) FROM pg_attribute a WHERE a.attrelid='pubburi.t_user_notebook'::regclass AND a.attnum>0 AND NOT a.attisdropped")){
                // Array dimensionality is part of the declared type, independently of empty data.
                String actual=rows.next()?rows.getString(1):null;
                if(!shape.equals(actual))throw new IllegalArgumentException("postgresql_notebook_shape_changed");
            }
        }
    }
    private static void verifyEmptyFeedback(Connection connection) throws SQLException {
        // The MySQL artifact has no feedback. Never certify a fresh import over PG-only data.
        try(var statement=connection.createStatement()) {
            statement.execute("LOCK TABLE pubburi.t_user_recommendation_feedback IN ACCESS SHARE MODE");
            try(var rows=statement.executeQuery("SELECT EXISTS(SELECT 1 FROM pubburi.t_user_recommendation_feedback)")) {
                if(!rows.next() || rows.getBoolean(1)) throw new IllegalArgumentException("postgresql_import_feedback_not_empty");
            }
            try(var rows=statement.executeQuery("""
                SELECT column_name,data_type,is_nullable,character_maximum_length,collation_schema,collation_name
                FROM information_schema.columns
                WHERE table_schema='pubburi' AND table_name='t_user_recommendation_feedback'
                ORDER BY ordinal_position
                """)) {
                if(!rows.next() || !"user_id".equals(rows.getString(1)) || !"character varying".equals(rows.getString(2))
                        || !"NO".equals(rows.getString(3)) || rows.getInt(4)!=100
                        || !"pubburi".equals(rows.getString(5)) || !"pubburi_ci".equals(rows.getString(6))
                        || !rows.next() || !"product_id".equals(rows.getString(1)) || !"integer".equals(rows.getString(2))
                        || !"NO".equals(rows.getString(3))
                        || !rows.next() || !"feedback".equals(rows.getString(1)) || !"character varying".equals(rows.getString(2))
                        || !"NO".equals(rows.getString(3)) || rows.getInt(4)!=14
                        || !"pg_catalog".equals(rows.getString(5)) || !"C".equals(rows.getString(6)) || rows.next()) {
                    throw new IllegalArgumentException("postgresql_import_feedback_shape_changed");
                }
            }
        }
    }
    static void reviewTags(Connection connection,boolean empty) throws SQLException {
        try(var s=connection.createStatement()) {
            // Base-table import locks prevent parent/child writes; verification retains its read-only RR snapshot.
            s.execute("LOCK TABLE pubburi.t_comment_taste_tag IN ACCESS SHARE MODE");
            try(var r=s.executeQuery("SELECT EXISTS(SELECT 1 FROM pubburi.t_comment_taste_tag)")) {
                if(!r.next() || (empty && r.getBoolean(1))) throw new IllegalArgumentException("postgresql_v1_review_tags_unsupported");
            }
            try(var r=s.executeQuery("SELECT column_name,data_type,is_nullable,character_maximum_length,collation_name,collation_schema FROM information_schema.columns WHERE table_schema='pubburi' AND table_name='t_comment_taste_tag' ORDER BY ordinal_position")) {
                if(!r.next() || !"comment_id".equals(r.getString(1)) || !"integer".equals(r.getString(2)) || !"NO".equals(r.getString(3))
                        || !r.next() || !"tag".equals(r.getString(1)) || !"character varying".equals(r.getString(2)) || !"NO".equals(r.getString(3)) || r.getInt(4)!=40 || !"C".equals(r.getString(5)) || !"pg_catalog".equals(r.getString(6)) || r.next())
                    throw new IllegalArgumentException("postgresql_review_tag_shape_changed");
            }
        }
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
        if(Set.of("t_comment_taste_tag.tag","t_product_feature_tag.tag","t_user_preference.survey_status","t_user_preference_value.kind","t_user_preference_value.value_text","t_product_search_relay_target.fingerprint").contains(key)) return "pg_catalog.C";
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
