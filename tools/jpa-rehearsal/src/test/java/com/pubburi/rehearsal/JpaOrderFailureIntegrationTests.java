package com.pubburi.rehearsal;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "PUBBURI_JPA_INTEGRATION", matches = "1")
class JpaOrderFailureIntegrationTests extends JpaOrderTestSupport {
    @DynamicPropertySource static void failureDatabase(DynamicPropertyRegistry properties) {
        String database = System.getenv("PUBBURI_JPA_FAILURE_DB_NAME"), token = System.getenv("PUBBURI_JPA_FAILURE_RUN_TOKEN"), url = System.getenv("PUBBURI_PG_URL");
        assertNotNull(database); assertNotNull(token); assertNotNull(url);
        assertNotEquals(database, System.getenv("PUBBURI_PG_DB_NAME"));
        properties.add("PUBBURI_PG_DB_NAME", () -> database); properties.add("PUBBURI_PG_RUN_TOKEN", () -> token);
        properties.add("PUBBURI_PG_URL", () -> url.substring(0, url.lastIndexOf('/') + 1) + database);
    }
    AutoCloseable trigger(JdbcTemplate sql, String table, String operation, String user) {
        assertEquals(required("PUBBURI_JPA_FAILURE_DB_NAME"), required("PUBBURI_PG_DB_NAME"));
        assertTrue(user.matches("ci_jpa_order_[a-f0-9]{32}"));
        String function = "pubburi.ci_jpa_order_failure";
        String key = table.equals("t_user") ? "id" : "user_id";
        sql.execute("CREATE FUNCTION " + function + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW." + key + "='" + user + "' THEN RAISE EXCEPTION 'authored order failure'; END IF; RETURN NEW; END $$");
        try { sql.execute("CREATE TRIGGER ci_jpa_order_failure AFTER " + operation + " ON pubburi." + table + " FOR EACH ROW EXECUTE FUNCTION " + function + "()"); }
        catch (Throwable error) { sql.execute("DROP FUNCTION " + function + "()"); throw error; }
        return () -> { try { sql.execute("DROP TRIGGER ci_jpa_order_failure ON pubburi." + table); } finally { sql.execute("DROP FUNCTION " + function + "()"); } };
    }
    @Test void failureAfterStampInsertRollsBackOrderDetailsTotalsCountersAndProducer() throws Exception {
        failedLateWrite("t_stamp", "INSERT");
    }
    @Test void failureAfterUserIncrementRollsBackTheStampAndAllEarlierWrites() throws Exception {
        failedLateWrite("t_user", "UPDATE");
    }
    private void failedLateWrite(String table, String operation) throws Exception {
        var sql = control(); var admin = loggedIn("ADMIN"); String user = addUser(); int product = create(admin, ownName()); var buyer = loginUser(user);
        try {
            var before = orderState(sql, user, product); var identity = sourceIdentity(sql);
            try (var fault = trigger(sql, table, operation, user)) {
                assertEquals(500, request(buyer, "POST", "/api/orders", order(product, 2)).status());
                assertEquals(before, orderState(sql, user, product)); assertEquals(identity, sourceIdentity(sql));
            }
            var result = request(buyer, "POST", "/api/orders", order(product, 2)); assertEquals(201, result.status(), result.body().toString());
            assertEquals(2400, result.body().path("data").path("totalPrice").intValue());
            assertEquals(2, sql.queryForObject("SELECT stamps FROM pubburi.t_user WHERE id=?", Integer.class, user));
            assertEquals(2, events(sql, product).size());
        } finally { clean(sql, user, product); }
    }
}
