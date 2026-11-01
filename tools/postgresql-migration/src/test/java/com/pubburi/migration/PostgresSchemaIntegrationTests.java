package com.pubburi.migration;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

/** Real PostgreSQL/TLS only: no H2 or application/JPA equivalence claim. */
@EnabledIfEnvironmentVariable(named="PUBBURI_PG_INTEGRATION", matches="1")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PostgresSchemaIntegrationTests {
    @TempDir Path temporary;

    Map<String, String> env(String purpose) {
        Map<String, String> result = new HashMap<>(System.getenv());
        if (!purpose.equals("FRESH")) {
            String database = result.get("PUBBURI_PG_" + purpose + "_DB_NAME");
            String token = result.get("PUBBURI_PG_" + purpose + "_RUN_TOKEN");
            assertNotNull(database); assertNotNull(token);
            String url = result.get("PUBBURI_PG_URL");
            result.put("PUBBURI_PG_URL", url.substring(0, url.lastIndexOf('/') + 1) + database);
            result.put("PUBBURI_PG_DB_NAME", database); result.put("PUBBURI_PG_RUN_TOKEN", token);
        }
        result.put("PUBBURI_PG_MUTATING", "1");
        return result;
    }

    long number(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next()); return result.getLong(1);
        }
    }

    String text(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next()); return result.getString(1);
        }
    }

    void reject(Connection connection, String sql, String state) throws SQLException {
        var savepoint = connection.setSavepoint();
        try (Statement statement = connection.createStatement()) {
            SQLException error = assertThrows(SQLException.class, () -> statement.execute(sql));
            assertEquals(state, error.getSQLState());
        } finally { connection.rollback(savepoint); }
    }

    @Test @Order(1) void freshInstallPreservesTypesAndReferencesAndReservesImportedIdentityHighWatermarks() throws Exception {
        Map<String, String> env = env("FRESH"); var settings = PostgresSchemaTool.Settings.from(env);
        PostgresSchemaTool.guard(settings);
        assertEquals(2, PostgresSchemaTool.run("migrate", env));
        assertEquals(0, PostgresSchemaTool.run("migrate", env));
        assertEquals(0, PostgresSchemaTool.run("validate", env));
        String epoch = "960dc638-0651-4b64-af81-f8c41fa08b04";
        String source = "0db714c6-ef29-4cda-8020-d3b2eb15bd81";
        String payload = "{\n \"productId\":41, \"sourceVersion\":1, \"deleted\":false, \"document\":{\"name\":\"가\",\"type\":\"탁주\",\"priceKrw\":7500,\"abvPercent\":5.555,\"orderCount\":0,\"tasteTags\":null,\"aromaTags\":[]}}\n";
        try (Connection connection = settings.connect(); Statement statement = connection.createStatement()) {
            assertEquals(18, number(connection, "SELECT count(*) FROM information_schema.tables WHERE table_schema='pubburi'"));
            assertEquals(30,SchemaIndexContract.actual(connection).size());
            assertEquals(SchemaIndexContract.expected(),SchemaIndexContract.actual(connection));
            connection.setAutoCommit(false);
            statement.execute("INSERT INTO pubburi.t_user VALUES('CaseUser','검증 회원','authored-not-login-hash','USER','" + epoch + "',0)");
            assertEquals(epoch, text(connection, "SELECT auth_epoch::text FROM pubburi.t_user WHERE id='caseuser'"));
            reject(connection, "INSERT INTO pubburi.t_user VALUES('caseuser','중복','fixture','USER','" + epoch + "',0)", "23505");
            assertEquals(1, number(connection, "SELECT ('Cafe' COLLATE pubburi.pubburi_ci = 'Café')::int"));
            try (var insert = connection.prepareStatement("INSERT INTO pubburi.t_product(id,name,type,price,img,abv,order_count) VALUES(?,?,'탁주',7500,'fixture',?,0)")) {
                float[] values = {5.1f, 5.55f, 5.555f};
                for (int i=0; i<values.length; i++) { insert.setInt(1,41+i); insert.setString(2,new String[]{"가","나","다"}[i]); insert.setFloat(3,values[i]); insert.executeUpdate(); }
            }
            assertEquals("{41,42,43}", text(connection, "SELECT array_agg(id ORDER BY name,id)::text FROM pubburi.t_product"));
            try (ResultSet result = statement.executeQuery("SELECT abv FROM pubburi.t_product WHERE id=43")) {
                assertTrue(result.next()); assertEquals(Float.floatToIntBits(5.555f), Float.floatToIntBits(result.getFloat(1)));
            }
            assertEquals(1, number(connection, "SELECT count(*) FROM pubburi.t_product WHERE abv BETWEEN 5.55::real AND 5.55::real"));
            assertEquals(0, number(connection, "SELECT count(*) FROM pubburi.t_product WHERE id=43 AND abv<=5.55::real"));
            statement.execute("INSERT INTO pubburi.t_product_feature(product_id,taste_known,aroma_known,source_name) VALUES(41,false,true,'authored fixture'),(42,true,false,'authored fixture')");
            statement.execute("INSERT INTO pubburi.t_product_feature_tag VALUES(42,'TASTE','Sweet'),(42,'TASTE','sweet')");
            assertEquals(2, number(connection, "SELECT count(*) FROM pubburi.t_product_feature_tag WHERE product_id=42"));
            assertEquals(1, number(connection, "SELECT count(*) FROM pubburi.t_product_feature WHERE product_id=41 AND NOT taste_known AND aroma_known"));
            reject(connection, "UPDATE pubburi.t_product_feature SET volume_ml=0 WHERE product_id=41", "23514");
            statement.execute("INSERT INTO pubburi.t_user_preference(user_id,survey_status,target_abv_percent,max_abv_percent) VALUES('CaseUser','COMPLETED',15.25,10)");
            assertEquals("15.25", text(connection, "SELECT target_abv_percent::text FROM pubburi.t_user_preference"));
            reject(connection, "UPDATE pubburi.t_user_preference SET survey_status='SKIPPED'", "23514");
            statement.execute("INSERT INTO pubburi.t_user_preference_value VALUES('CaseUser','TASTE','sweet')");
            statement.execute("INSERT INTO pubburi.t_user_favorite VALUES('CaseUser',41,'2026-11-01T15:00:00.123456Z')");
            reject(connection, "INSERT INTO pubburi.t_user_favorite(user_id,product_id) VALUES('CaseUser',41)", "23505");
            assertEquals("123456", text(connection, "SELECT to_char(created_at,'US') FROM pubburi.t_user_favorite"));
            statement.execute("INSERT INTO pubburi.t_order(o_id,user_id,order_type,total_price,price_basis) VALUES(51,'CaseUser','DEMO',15000,'SNAPSHOT')");
            statement.execute("INSERT INTO pubburi.t_order_detail VALUES(61,51,41,2,'가','탁주','fixture',7500,15000)");
            statement.execute("INSERT INTO pubburi.t_comment(id,user_id,product_id,rating) VALUES(81,'CaseUser',41,4.5)");
            assertEquals(4.5f, Float.parseFloat(text(connection, "SELECT rating::text FROM pubburi.t_comment WHERE id=81")));
            statement.execute("INSERT INTO pubburi.t_product_search_version VALUES(41,1),(9000,1)");
            try (var insert = connection.prepareStatement("INSERT INTO pubburi.t_product_search_outbox(event_id,product_id,source_version,deleted,payload) VALUES(71,41,1,false,?::json)")) {
                insert.setString(1,payload); insert.executeUpdate();
            }
            assertEquals(payload, text(connection, "SELECT payload::text FROM pubburi.t_product_search_outbox WHERE event_id=71"));
            statement.execute("INSERT INTO pubburi.t_product_search_delivery(event_id) VALUES(71)");
            statement.execute("INSERT INTO pubburi.t_product_search_outbox(event_id,product_id,source_version,deleted,payload) VALUES(72,9000,1,true,'{\"productId\":9000,\"sourceVersion\":1,\"deleted\":true,\"document\":null}')");
            statement.execute("INSERT INTO pubburi.t_product_search_delivery(event_id,status,delivered_at) VALUES(72,'DELIVERED','2026-11-01T15:00:00.123456Z')");
            statement.execute("UPDATE pubburi.t_product_search_delivery SET status='IN_FLIGHT',claim_token='b041428e-1a83-4e16-a23e-79031da71235',lease_until='2026-11-01T15:02:00.123456Z' WHERE event_id=71");
            String identity="{ \"source_id\":\""+source+"\",\"cluster_uuid\":\"authored-cluster\",\"index_uuid\":\"authored-index\",\"index_name\":\"pubburi-products-v1-fixture\",\"schema\":\"products-v1\",\"data_kind\":\"backend_product_events\" }";
            try(var insert=connection.prepareStatement("INSERT INTO pubburi.t_product_search_relay_target VALUES(1,?::uuid,?,?::json,'2026-11-01T15:00:00.123456Z')")) {
                insert.setString(1,source);insert.setString(2,"a".repeat(64));insert.setString(3,identity);insert.executeUpdate();
            }
            assertEquals(source, text(connection, "SELECT source_id::text FROM pubburi.t_product_search_relay_target"));
            assertEquals(identity,text(connection,"SELECT identity_json::text FROM pubburi.t_product_search_relay_target"));
            assertEquals("a".repeat(64),text(connection,"SELECT fingerprint FROM pubburi.t_product_search_relay_target"));
            assertEquals(1,number(connection,"SELECT count(*) FROM pubburi.t_product_search_delivery WHERE event_id=72 AND status='DELIVERED' AND to_char(delivered_at,'US')='123456'"));
            reject(connection, "UPDATE pubburi.t_product_search_delivery SET status='DELIVERED' WHERE event_id=71", "23514");
            reject(connection, "DELETE FROM pubburi.t_product_search_outbox WHERE event_id=71", "23001");
            statement.execute("UPDATE pubburi.t_product SET price=9999,name='현재 상품' WHERE id=41");
            statement.execute("DELETE FROM pubburi.t_product WHERE id=41");
            assertEquals(1, number(connection, "SELECT count(*) FROM pubburi.t_order_detail WHERE d_id=61 AND product_id IS NULL AND unit_price=7500 AND sum_price=15000"));
            assertEquals(0, number(connection, "SELECT count(*) FROM pubburi.t_product_feature WHERE product_id=41"));
            assertEquals(payload, text(connection, "SELECT payload::text FROM pubburi.t_product_search_outbox WHERE event_id=71"));
            statement.execute("DELETE FROM pubburi.t_user WHERE id='CaseUser'");
            assertEquals(1, number(connection, "SELECT count(*) FROM pubburi.t_order WHERE o_id=51 AND user_id IS NULL AND total_price=15000"));
            for (String table : new String[]{"t_user_preference","t_user_preference_value","t_user_favorite","t_comment"})
                assertEquals(0, number(connection, "SELECT count(*) FROM pubburi." + table));
            connection.commit();
        }

        Path manifest = temporary.resolve(".pubburi/identities.txt"); Files.createDirectories(manifest.getParent());
        String reservations = "pubburi-identities-v1\nt_product=9001\nt_order=501\nt_order_detail=601\nt_comment=701\nt_stamp=801\nt_market=901\nt_product_search_outbox=1001\n";
        Files.writeString(manifest,reservations); env.put("PUBBURI_PG_IDENTITY_FILE",manifest.toString());
        assertEquals(7, PostgresSchemaTool.run("reserve-identities",env));
        try (Connection connection = settings.connect(); Statement statement = connection.createStatement()) {
            assertEquals(9001, number(connection, "INSERT INTO pubburi.t_product(name,type,price,img,abv) VALUES('새 상품','탁주',1,'fixture',0) RETURNING id"));
            assertEquals(1001, number(connection, "SELECT nextval('pubburi.t_product_search_outbox_event_id_seq')"));
            Files.writeString(manifest,reservations.replace("t_product=9001","t_product=9002").replace("t_product_search_outbox=1001","t_product_search_outbox=1002"));
            assertEquals(0, PostgresSchemaTool.run("reserve-identities",env));
            long previous = number(connection,"SELECT last_value FROM pubburi.t_product_id_seq");
            Files.writeString(manifest,reservations.replace("t_product=9001","t_product=1"));
            assertThrows(IllegalArgumentException.class, () -> PostgresSchemaTool.run("reserve-identities",env));
            assertEquals(previous, number(connection,"SELECT last_value FROM pubburi.t_product_id_seq"));
        }
        Path drift = temporary.resolve("drift"); Files.createDirectories(drift);
        String original;
        try (var input = getClass().getResourceAsStream("/db/postgresql/V1__business_schema.sql")) { original = new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8); }
        Files.writeString(drift.resolve("V1__business_schema.sql"),original + "\n-- authored checksum mutation\n");
        assertThrows(FlywayException.class, () -> PostgresSchemaTool.configuration(settings,"filesystem:"+drift).load().validate());
        assertEquals(0, PostgresSchemaTool.run("validate",env));
    }

    @Test @Order(2) void failedTransactionalDdlDoesNotLeavePartialBusinessTablesAndCanRecover() throws Exception {
        Map<String,String> env=env("RECOVERY"); var settings=PostgresSchemaTool.Settings.from(env);
        PostgresSchemaTool.guard(settings);
        assertThrows(FlywayException.class, () -> PostgresSchemaTool.configuration(settings,"classpath:db/failed-postgresql").load().migrate());
        try (Connection connection=settings.connect()) {
            assertEquals(0,number(connection,"SELECT count(*) FROM information_schema.tables WHERE table_schema='pubburi' AND table_name LIKE 'transaction_%'"));
        }
        assertEquals(2,PostgresSchemaTool.run("migrate",env));
        assertEquals(0,PostgresSchemaTool.run("validate",env));
    }

    @Test @Order(3) void anUntrackedExistingSchemaAndWrongMarkerAreRejectedWithoutAdoptionOrDeletion() throws Exception {
        Map<String,String> env=env("BLOCKED"); var settings=PostgresSchemaTool.Settings.from(env);
        PostgresSchemaTool.guard(settings);
        try(Connection connection=settings.connect();Statement statement=connection.createStatement()) {
            statement.execute("CREATE SCHEMA pubburi");
            statement.execute("CREATE TABLE pubburi.unmanaged_fixture(value INTEGER PRIMARY KEY)");
            statement.execute("INSERT INTO pubburi.unmanaged_fixture VALUES(73)");
        }
        assertThrows(FlywayException.class, () -> PostgresSchemaTool.run("migrate",env));
        env.put("PUBBURI_PG_RUN_TOKEN","wrong-authored-marker");
        assertThrows(IllegalArgumentException.class, () -> PostgresSchemaTool.run("migrate",env));
        try(Connection connection=settings.connect()) {
            assertEquals(73,number(connection,"SELECT value FROM pubburi.unmanaged_fixture"));
            assertEquals(1,number(connection,"SELECT count(*) FROM information_schema.tables WHERE table_schema='pubburi'"));
        }
    }

    @Test @Order(4)
    @EnabledIfEnvironmentVariable(named="PUBBURI_PG_ADMIN_USER", matches="[a-z][a-z0-9_]+")
    void aRealSecondSequencePermissionFailureRollsBackTheFirstSequenceRestart() throws Exception {
        Map<String,String> env=env("FRESH");var settings=PostgresSchemaTool.Settings.from(env);
        PostgresSchemaTool.guard(settings); // Normal operations remain on the guarded, non-superuser connection.
        String adminUser=env.get("PUBBURI_PG_ADMIN_USER");String adminPassword=env.get("PUBBURI_PG_ADMIN_PASSWORD");
        assertNotNull(adminPassword);assertTrue(settings.user().matches("[a-z][a-z0-9_]+"));
        var properties=settings.properties();properties.setProperty("user",adminUser);properties.setProperty("password",adminPassword);
        Map<String,Long> reservations=new java.util.LinkedHashMap<>();
        Map<String,String> before=new java.util.LinkedHashMap<>();
        try(Connection normal=settings.connect()) {
            for(var identity:IdentityReservations.IDENTITIES) {
                before.put(identity.table(),text(normal,"SELECT last_value||':'||is_called FROM "+identity.sequence()));
                reservations.put(identity.table(),number(normal,"SELECT last_value+100 FROM "+identity.sequence()));
            }
        }
        try(Connection control=java.sql.DriverManager.getConnection(settings.url(),properties);Statement ddl=control.createStatement()) {
            // Only an owned, ephemeral CI cluster provides this failure-injection administrator.
            ddl.execute("CREATE ROLE ci_sequence_failure_owner NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE");
            ddl.execute("GRANT USAGE,CREATE ON SCHEMA pubburi TO ci_sequence_failure_owner");
            ddl.execute("ALTER TABLE pubburi.t_order OWNER TO ci_sequence_failure_owner");
            try {
                ddl.execute("GRANT ALL ON pubburi.t_order TO "+settings.user());
                ddl.execute("GRANT SELECT ON SEQUENCE pubburi.t_order_o_id_seq TO "+settings.user());
                SQLException error=assertThrows(SQLException.class,()->IdentityReservations.apply(settings,reservations));
                assertEquals("42501",error.getSQLState());
                assertTrue(error.getMessage().contains("t_order_o_id_seq"), "The failure must be the second sequence ALTER, after product RESTART");
            } finally { ddl.execute("ALTER TABLE pubburi.t_order OWNER TO "+settings.user()); }
        }
        try(Connection normal=settings.connect()) {
            for(var identity:IdentityReservations.IDENTITIES)
                assertEquals(before.get(identity.table()),text(normal,"SELECT last_value||':'||is_called FROM "+identity.sequence()));
            assertEquals(15000,number(normal,"SELECT total_price FROM pubburi.t_order WHERE o_id=51"));
        }
    }
}
