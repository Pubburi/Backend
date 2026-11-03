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
class JpaFavoriteFailureIntegrationTests extends JpaAccountTestSupport {
    @DynamicPropertySource static void failureDatabase(DynamicPropertyRegistry properties) {
        String database = System.getenv("PUBBURI_JPA_FAILURE_DB_NAME"), token = System.getenv("PUBBURI_JPA_FAILURE_RUN_TOKEN"), url = System.getenv("PUBBURI_PG_URL");
        assertNotNull(database); assertNotNull(token); assertNotNull(url); assertNotEquals(database, System.getenv("PUBBURI_PG_DB_NAME"));
        properties.add("PUBBURI_PG_DB_NAME", () -> database); properties.add("PUBBURI_PG_RUN_TOKEN", () -> token);
        properties.add("PUBBURI_PG_URL", () -> url.substring(0, url.lastIndexOf('/') + 1) + database);
    }
    AutoCloseable trigger(JdbcTemplate sql, String operation, String user, int product) {
        assertEquals(required("PUBBURI_JPA_FAILURE_DB_NAME"), required("PUBBURI_PG_DB_NAME")); assertTrue(user.matches("ci_jpa_account_[a-f0-9]{32}")); assertTrue(Set.of("INSERT", "DELETE").contains(operation));
        String row = operation.equals("DELETE") ? "OLD" : "NEW";
        sql.execute("CREATE FUNCTION pubburi.ci_jpa_favorite_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF " + row + ".user_id='" + user + "' AND " + row + ".product_id=" + product + " THEN RAISE EXCEPTION 'authored favorite failure'; END IF; RETURN " + row + "; END $$");
        try { sql.execute("CREATE TRIGGER ci_jpa_favorite_failure AFTER " + operation + " ON pubburi.t_user_favorite FOR EACH ROW EXECUTE FUNCTION pubburi.ci_jpa_favorite_failure()"); }
        catch (Throwable error) { sql.execute("DROP FUNCTION pubburi.ci_jpa_favorite_failure()"); throw error; }
        return () -> { try { sql.execute("DROP TRIGGER ci_jpa_favorite_failure ON pubburi.t_user_favorite"); } finally { sql.execute("DROP FUNCTION pubburi.ci_jpa_favorite_failure()"); } };
    }
    @Test void failedInsertRollsBackMembershipWithoutChangingAnyBusinessState() throws Exception { failed("INSERT"); }
    @Test void failedDeleteKeepsTheFirstTimestampAndMembership() throws Exception { failed("DELETE"); }
    private void failed(String operation) throws Exception {
        var sql = control(); var admin = loggedIn("ADMIN"); String user = join(); int product = create(admin, ownName());
        try {
            var owner = login(user, password); String path = "/api/users/me/favorites/" + product;
            if (operation.equals("DELETE")) assertEquals(200, request(owner, "PUT", path, null).status());
            var account = accountState(sql, user); var producer = state(sql, product); var identity = sourceIdentity(sql);
            String method = operation.equals("INSERT") ? "PUT" : "DELETE";
            try (var fault = trigger(sql, operation, user, product)) {
                assertEquals(500, request(owner, method, path, null).status()); assertEquals(account, accountState(sql, user)); assertEquals(producer, state(sql, product)); assertEquals(identity, sourceIdentity(sql));
                assertEquals(operation.equals("DELETE"), request(owner, "GET", path, null).body().path("data").path("favorited").booleanValue());
            }
            assertEquals(200, request(owner, method, path, null).status());
        } finally { clean(sql, user, product, List.of()); }
    }
}
