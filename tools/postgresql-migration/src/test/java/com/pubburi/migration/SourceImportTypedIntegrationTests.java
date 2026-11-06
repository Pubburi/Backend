package com.pubburi.migration;

import static org.junit.jupiter.api.Assertions.*;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named="PUBBURI_PG_IMPORT_INTEGRATION",matches="1")
class SourceImportTypedIntegrationTests {
    @Test void authoredSourceTypesPrivateStatesAndDeletedReferencesAreCopiedSeparately() throws Exception {
        var helper=new SourceImportIntegrationTests();var env=helper.env("TYPED");var settings=PostgresSchemaTool.Settings.from(env);
        PostgresSchemaTool.guard(settings);assertEquals(2,PostgresSchemaTool.configuration(settings,PostgresSchemaTool.LOCATION).target("2").load().migrate().migrationsExecuted);
        var source=SourceImportTool.input(env);assertEquals(2,source.version());assertEquals(17,source.report().tables());
        try(Connection c=settings.connect()) {
            var before=SourceImportIntegrationTests.sequences(c);
            assertThrows(org.flywaydb.core.api.exception.FlywayValidateException.class,()->SourceImportTool.run(env));
            SourceImportIntegrationTests.empty(c);assertEquals(before,SourceImportIntegrationTests.sequences(c));
        }
        assertEquals(2,PostgresSchemaTool.run("migrate",env));
        try(Connection c=settings.connect()) {
            var before=SourceImportIntegrationTests.sequences(c);
            SourceImportIntegrationTests.failure(c,"t_comment_taste_tag","ci_import_review_tag_failure",false);
            try {assertThrows(SQLException.class,()->SourceImportTool.run(env));SourceImportIntegrationTests.empty(c);assertEquals(0,SourceImportIntegrationTests.number(c,"SELECT count(*) FROM pubburi.t_comment_taste_tag"));assertEquals(before,SourceImportIntegrationTests.sequences(c));}
            finally {SourceImportIntegrationTests.removeFailure(c,"t_comment_taste_tag","ci_import_review_tag_failure");}
        }
        assertEquals(source.report(),SourceImportTool.run(env));
        var readOnly=new HashMap<>(env);readOnly.remove("PUBBURI_PG_MUTATING");assertEquals(source.report(),SourceImportTool.verify(readOnly));
        try(Connection c=PostgresSchemaTool.Settings.from(env).connect()) {
            SourceImportTool.compare(c,source);
            assertEquals(1,SourceImportIntegrationTests.number(c,"SELECT count(*) FROM pubburi.t_product_feature WHERE volume_ml=500 AND NOT taste_known AND aroma_known"));
            assertEquals(1,SourceImportIntegrationTests.number(c,"SELECT count(*) FROM pubburi.t_product_feature_tag WHERE kind='AROMA' AND tag='검수향'"));
            assertEquals(1,SourceImportIntegrationTests.number(c,"SELECT count(*) FROM pubburi.t_user_preference WHERE target_abv_percent=15.25 AND max_abv_percent=10.00 AND min_abv_percent=0.00 AND min_price_krw=0 AND NOT taste_known"));
            assertEquals(1,SourceImportIntegrationTests.number(c,"SELECT count(*) FROM pubburi.t_user_favorite WHERE to_char(created_at,'US')='123456'"));
            assertEquals(1,SourceImportIntegrationTests.number(c,"SELECT count(*) FROM pubburi.t_order o JOIN pubburi.t_order_detail d ON d.order_id=o.o_id WHERE o.user_id IS NULL AND d.product_id IS NULL AND o.total_price=15000 AND d.unit_price=7500 AND d.sum_price=15000"));
            assertEquals(1,SourceImportIntegrationTests.number(c,"SELECT count(*) FROM pubburi.t_product_search_delivery WHERE status='IN_FLIGHT' AND attempts=2 AND to_char(lease_until,'US')='123456'"));
            assertEquals(1,SourceImportIntegrationTests.number(c,"SELECT count(*) FROM pubburi.t_product_search_delivery WHERE status='DELIVERED' AND to_char(delivered_at,'US')='654321'"));
            assertEquals(1,SourceImportIntegrationTests.number(c,"SELECT count(*) FROM pubburi.t_product_search_relay_target WHERE fingerprint=repeat('a',64) AND identity_json IS NOT NULL AND bound_at IS NOT NULL"));
            var expected=new HashSet<String>();for(var row:SourceIntegrationTests.table(source.data(),"t_comment_taste_tag").path("rows"))expected.add(row.get(1).textValue());
            var actual=new HashSet<String>();try(var statement=c.createStatement();var rows=statement.executeQuery("SELECT tag FROM pubburi.t_comment_taste_tag")){while(rows.next())actual.add(rows.getString(1));}
            assertEquals(expected,actual);assertTrue(actual.size()>20);assertTrue(actual.contains("  SWEET  "));assertTrue(actual.contains("Line\nBreak"));assertTrue(actual.contains("🌾".repeat(40)));
            assertEquals(1,SourceImportIntegrationTests.number(c,"SELECT count(*) FROM pubburi.t_comment_taste_tag WHERE char_length(tag)=40 AND octet_length(tag)=160"));
            try(var statement=c.createStatement()) {
                var duplicate=assertThrows(SQLException.class,()->statement.executeUpdate("INSERT INTO pubburi.t_comment_taste_tag SELECT comment_id,tag FROM pubburi.t_comment_taste_tag LIMIT 1"));assertEquals("23505",duplicate.getSQLState());
                int missing=Integer.parseInt(source.data().path("sourceNext").path("t_comment").textValue());
                var orphan=assertThrows(SQLException.class,()->statement.executeUpdate("INSERT INTO pubburi.t_comment_taste_tag VALUES("+missing+",'authored absent parent')"));assertEquals("23503",orphan.getSQLState());
                statement.executeUpdate("UPDATE pubburi.t_comment_taste_tag SET tag='authored changed opinion' WHERE tag='  SWEET  '");
                try {assertThrows(IllegalArgumentException.class,()->SourceImportTool.verify(readOnly));}
                finally {statement.executeUpdate("UPDATE pubburi.t_comment_taste_tag SET tag='  SWEET  ' WHERE tag='authored changed opinion'");}
                statement.execute("ALTER TABLE pubburi.t_comment_taste_tag ALTER COLUMN tag TYPE VARCHAR(41) COLLATE \"C\"");
                try {assertThrows(IllegalArgumentException.class,()->SourceImportTool.verify(readOnly));}
                finally {statement.execute("ALTER TABLE pubburi.t_comment_taste_tag ALTER COLUMN tag TYPE VARCHAR(40) COLLATE \"C\"");}
                String owner=statement.enquoteIdentifier(settings.user(),true);statement.execute("REVOKE SELECT ON pubburi.t_comment_taste_tag FROM "+owner);
                try {var denied=assertThrows(Exception.class,()->SourceImportTool.verify(readOnly));assertTrue(denied instanceof SQLException error && "42501".equals(error.getSQLState()) || denied instanceof IllegalArgumentException && "postgresql_review_tag_unreadable".equals(denied.getMessage()));}
                finally {statement.execute("GRANT SELECT ON pubburi.t_comment_taste_tag TO "+owner);}
            }
            assertEquals(source.report(),SourceImportTool.verify(readOnly));
        }
    }
}
