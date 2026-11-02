package com.pubburi.rehearsal;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Fault DDL is reserved for the separate owned failure DB and removed in every finally block. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "PUBBURI_JPA_INTEGRATION", matches = "1")
class JpaProductFailureIntegrationTests extends JpaWriteTestSupport {
    @DynamicPropertySource
    static void failureDatabase(DynamicPropertyRegistry properties) {
        String database = System.getenv("PUBBURI_JPA_FAILURE_DB_NAME");
        String token = System.getenv("PUBBURI_JPA_FAILURE_RUN_TOKEN");
        String url = System.getenv("PUBBURI_PG_URL");
        assertNotNull(database); assertNotNull(token); assertNotNull(url);
        assertNotEquals(database, System.getenv("PUBBURI_PG_DB_NAME"));
        properties.add("PUBBURI_PG_DB_NAME", () -> database);
        properties.add("PUBBURI_PG_RUN_TOKEN", () -> token);
        properties.add("PUBBURI_PG_URL", () -> url.substring(0, url.lastIndexOf('/') + 1) + database);
    }

    AutoCloseable trigger(JdbcTemplate sql, String table, String operation, String condition) {
        assertEquals(required("PUBBURI_JPA_FAILURE_DB_NAME"), required("PUBBURI_PG_DB_NAME"));
        String function = "pubburi.ci_jpa_owned_failure";
        sql.execute("CREATE FUNCTION " + function + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF "
            + condition + " THEN RAISE EXCEPTION 'authored JPA failure'; END IF; RETURN NEW; END $$");
        try {
            sql.execute("CREATE TRIGGER ci_jpa_owned_failure AFTER " + operation + " ON pubburi." + table
                + " FOR EACH ROW EXECUTE FUNCTION " + function + "()");
        } catch (Throwable error) {
            sql.execute("DROP FUNCTION " + function + "()");
            throw error;
        }
        return () -> {
            try { sql.execute("DROP TRIGGER ci_jpa_owned_failure ON pubburi." + table); }
            finally { sql.execute("DROP FUNCTION " + function + "()"); }
        };
    }

    @Test void marketFailureStillRollsBackAfterUpdateInTheSeparateFailureDatabase() throws Exception {
        var sql = control(); var admin = loggedIn("ADMIN"); String name = ownName();
        var market = Map.of("name", name, "latitude", 37.1, "longitude", 127.1, "image", "authored.png");
        var created = request(admin, "POST", "/api/admin/markets", market);
        assertEquals(201, created.status()); int id = created.body().path("data").path("id").intValue();
        var before = sql.queryForMap("SELECT * FROM pubburi.t_market WHERE id=? AND name=?", id, name);
        try {
            try (var fault = trigger(sql, "t_market", "UPDATE", "NEW.id=" + id + " AND NEW.name='" + name + "_failed'")) {
                var changed = Map.of("name", name + "_failed", "latitude", 38.2, "longitude", 128.2, "image", "authored.png");
                assertEquals(500, request(admin, "PUT", "/api/admin/markets/" + id, changed).status());
                assertEquals(before, sql.queryForMap("SELECT * FROM pubburi.t_market WHERE id=?", id));
            }
        } finally { assertEquals(200, request(admin, "DELETE", "/api/admin/markets/" + id, null).status()); }
    }

    @Test void eventInsertFailureRollsBackProductFeaturesTagsVersionAndDeletionHistory() throws Exception {
        var sql = control(); var admin = loggedIn("ADMIN"); String name = ownName(); int id = create(admin, name);
        String user = "ci_jpa_failure_" + UUID.randomUUID().toString().replace("-", "");
        sql.update("INSERT INTO pubburi.t_user(id,name,password_hash,role,auth_epoch,stamps) VALUES(?,?,'authored-no-login','USER',CAST(? AS uuid),0)", user, user, UUID.randomUUID().toString());
        int order = sql.queryForObject("INSERT INTO pubburi.t_order(user_id,order_type,order_table,total_price,price_basis) VALUES(?,'table','ci_jpa_failure',1200,'ORDER_SNAPSHOT') RETURNING o_id", Integer.class, user);
        sql.update("INSERT INTO pubburi.t_order_detail(order_id,product_id,quantity,product_name,product_type,product_img,unit_price,sum_price) VALUES(?,?,1,?,'증류주','authored.png',1200,1200)", order, id, name);
        try {
            assertEquals(200, request(admin, "PUT", "/api/admin/products/" + id + "/features", Map.of("tasteTags", List.of("dry"), "aromaTags", List.of("floral"), "sourceName", "Authored before failure")).status());
            var before = state(sql, id); var identity = sourceIdentity(sql);
            assertEquals(name, sql.queryForObject("SELECT name FROM pubburi.t_product WHERE id=?", String.class, id));
            // Exact freshly authored product ID; handles a deleted product too, without touching source events.
            try (var fault = trigger(sql, "t_product_search_outbox", "INSERT", "NEW.product_id=" + id)) {
                assertEquals(500, request(admin, "PUT", "/api/admin/products/" + id, product(name + " failed", 9900)).status());
                assertEquals(before, state(sql, id));
                assertEquals(500, request(admin, "PUT", "/api/admin/products/" + id + "/features", Map.of("tasteTags", List.of("new"), "sourceName", "Authored failed replacement")).status());
                assertEquals(before, state(sql, id));
                assertEquals(500, request(admin, "DELETE", "/api/admin/products/" + id, null).status());
                assertEquals(before, state(sql, id));
                assertEquals(identity, sourceIdentity(sql));
            }
            // Removing the fault, rather than retrying blindly, must let the same transaction path succeed.
            assertEquals(200, request(admin, "PUT", "/api/admin/products/" + id, product(name + " recovered", 2300)).status());
            assertEquals(3, events(sql, id).size());
        } finally {
            assertEquals(200, request(admin, "DELETE", "/api/admin/products/" + id, null).status());
            sql.update("DELETE FROM pubburi.t_order WHERE o_id=? AND user_id=?", order, user);
            sql.update("DELETE FROM pubburi.t_user WHERE id=?", user);
        }
    }

    @Test void deliveryInsertFailureRollsBackTheAlreadyInsertedEventAndBusinessRow() throws Exception {
        var sql = control(); var admin = loggedIn("ADMIN"); String name = ownName(); int id = create(admin, name);
        try {
            var before = state(sql, id);
            String condition = "EXISTS (SELECT 1 FROM pubburi.t_product_search_outbox e WHERE e.event_id=NEW.event_id AND e.product_id=" + id + ")";
            try (var fault = trigger(sql, "t_product_search_delivery", "INSERT", condition)) {
                assertEquals(500, request(admin, "PUT", "/api/admin/products/" + id, product(name + " must rollback", 9900)).status());
                assertEquals(before, state(sql, id));
                assertEquals(0, sql.queryForObject("SELECT count(*) FROM pubburi.t_product_search_outbox WHERE product_id=? AND source_version=2", Integer.class, id));
            }
            assertEquals(200, request(admin, "PUT", "/api/admin/products/" + id, product(name + " recovered", 2300)).status());
            assertEquals(List.of(1L, 2L), events(sql, id).stream().map(e -> e.get("source_version")).toList());
        } finally { assertEquals(200, request(admin, "DELETE", "/api/admin/products/" + id, null).status()); }
    }

    @Test void failedIdentityInsertLeavesNoProductOrProducerState() throws Exception {
        var sql = control(); var admin = loggedIn("ADMIN"); String name = ownName();
        int events = sql.queryForObject("SELECT count(*) FROM pubburi.t_product_search_outbox", Integer.class);
        int deliveries = sql.queryForObject("SELECT count(*) FROM pubburi.t_product_search_delivery", Integer.class);
        int versions = sql.queryForObject("SELECT count(*) FROM pubburi.t_product_search_version", Integer.class);
        try (var fault = trigger(sql, "t_product", "INSERT", "NEW.name='" + name + "'")) {
            assertEquals(500, request(admin, "POST", "/api/admin/products", product(name, 1200)).status());
            assertEquals(0, sql.queryForObject("SELECT count(*) FROM pubburi.t_product WHERE name=?", Integer.class, name));
            assertEquals(events, sql.queryForObject("SELECT count(*) FROM pubburi.t_product_search_outbox", Integer.class));
            assertEquals(deliveries, sql.queryForObject("SELECT count(*) FROM pubburi.t_product_search_delivery", Integer.class));
            assertEquals(versions, sql.queryForObject("SELECT count(*) FROM pubburi.t_product_search_version", Integer.class));
        }
        // Identity gaps caused by rollback are expected. The sequence is never reset.
        int created = create(admin, name);
        assertEquals(200, request(admin, "DELETE", "/api/admin/products/" + created, null).status());
    }
}
