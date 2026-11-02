package com.pubburi.rehearsal;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "PUBBURI_JPA_INTEGRATION", matches = "1")
class JpaAccountFailureIntegrationTests extends JpaAccountTestSupport {
    @DynamicPropertySource static void failureDatabase(DynamicPropertyRegistry properties) {
        String database = System.getenv("PUBBURI_JPA_FAILURE_DB_NAME"), token = System.getenv("PUBBURI_JPA_FAILURE_RUN_TOKEN"), url = System.getenv("PUBBURI_PG_URL");
        assertNotNull(database); assertNotNull(token); assertNotNull(url); assertNotEquals(database, System.getenv("PUBBURI_PG_DB_NAME"));
        properties.add("PUBBURI_PG_DB_NAME", () -> database); properties.add("PUBBURI_PG_RUN_TOKEN", () -> token);
        properties.add("PUBBURI_PG_URL", () -> url.substring(0, url.lastIndexOf('/') + 1) + database);
    }
    AutoCloseable trigger(JdbcTemplate sql, String operation, String id) {
        assertEquals(required("PUBBURI_JPA_FAILURE_DB_NAME"), required("PUBBURI_PG_DB_NAME")); assertTrue(id.matches("ci_jpa_account_[a-f0-9]{32}"));
        String row = operation.equals("DELETE") ? "OLD" : "NEW";
        sql.execute("CREATE FUNCTION pubburi.ci_jpa_account_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF " + row + ".id='" + id + "' THEN RAISE EXCEPTION 'authored account failure'; END IF; RETURN " + row + "; END $$");
        try { sql.execute("CREATE TRIGGER ci_jpa_account_failure AFTER " + operation + " ON pubburi.t_user FOR EACH ROW EXECUTE FUNCTION pubburi.ci_jpa_account_failure()"); }
        catch (Throwable error) { sql.execute("DROP FUNCTION pubburi.ci_jpa_account_failure()"); throw error; }
        return () -> { try { sql.execute("DROP TRIGGER ci_jpa_account_failure ON pubburi.t_user"); } finally { sql.execute("DROP FUNCTION pubburi.ci_jpa_account_failure()"); } };
    }
    @Test void failedAdminCredentialUpdatePreservesOldHashEpochRoleAndLiveSessions() throws Exception {
        var sql = control(); var admin = loggedIn("ADMIN"); String id = join();
        try {
            var owner = login(id, password); var before = accountState(sql, id);
            try (var fault = trigger(sql, "UPDATE", id)) {
                assertEquals(500, request(admin, "PUT", "/api/admin/users/" + id, Map.of("name", "Authored failed name", "pass", "Authored-failed-password", "stamps", 99, "role", "ADMIN")).status());
                assertEquals(before, accountState(sql, id));
                assertEquals(200, request(owner, "GET", "/api/auth/me", null).status());
                assertEquals(401, request(client(), "POST", "/api/auth/login", Map.of("id", id, "pass", "Authored-failed-password")).status());
            }
            assertEquals(200, profile(login(id, password), id).status());
            assertEquals(200, request(admin, "PUT", "/api/admin/users/" + id, Map.of("name", "Authored recovered name")).status());
        } finally { clean(sql, id, null, List.of()); }
    }
    @Test void failedUserDeleteRollsBackNotesReferencesStampsAndPrivateCascades() throws Exception {
        var sql = control(); var admin = loggedIn("ADMIN"); String id = join(); int product = create(admin, ownName()); var ids = new ArrayList<Integer>();
        try {
            var owner = login(id, password);
            var order = request(owner, "POST", "/api/orders", Map.of("orderTable", "authored note", "details", List.of(Map.of("productId", product, "quantity", 2))));
            assertEquals(201, order.status()); ids.add(order.body().path("data").path("oId").intValue()); children(sql, id, product);
            var before = accountState(sql, id); var productBefore = state(sql, product); var source = sourceIdentity(sql);
            try (var fault = trigger(sql, "DELETE", id)) {
                assertEquals(500, request(admin, "DELETE", "/api/admin/users/" + id, null).status());
                assertEquals(before, accountState(sql, id)); assertEquals(productBefore, state(sql, product)); assertEquals(source, sourceIdentity(sql));
                assertEquals(200, profile(owner, id).status());
            }
            assertEquals(200, request(admin, "DELETE", "/api/admin/users/" + id, null).status()); assertEquals(401, profile(owner, id).status());
            var orphan = sql.queryForMap("SELECT user_id,order_table,total_price FROM pubburi.t_order WHERE o_id=?", ids.get(0));
            assertNull(orphan.get("user_id")); assertNull(orphan.get("order_table")); assertEquals(2400, orphan.get("total_price"));
        } finally { clean(sql, id, product, ids); }
    }
}
