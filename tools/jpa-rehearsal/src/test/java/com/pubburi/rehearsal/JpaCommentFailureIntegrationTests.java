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
class JpaCommentFailureIntegrationTests extends JpaAccountTestSupport {
    @DynamicPropertySource static void failureDatabase(DynamicPropertyRegistry properties) {
        String database = System.getenv("PUBBURI_JPA_FAILURE_DB_NAME"), token = System.getenv("PUBBURI_JPA_FAILURE_RUN_TOKEN"), url = System.getenv("PUBBURI_PG_URL");
        assertNotNull(database); assertNotNull(token); assertNotNull(url); assertNotEquals(database, System.getenv("PUBBURI_PG_DB_NAME"));
        properties.add("PUBBURI_PG_DB_NAME", () -> database); properties.add("PUBBURI_PG_RUN_TOKEN", () -> token);
        properties.add("PUBBURI_PG_URL", () -> url.substring(0, url.lastIndexOf('/') + 1) + database);
    }
    AutoCloseable trigger(JdbcTemplate sql, String operation, String condition) {
        assertEquals(required("PUBBURI_JPA_FAILURE_DB_NAME"), required("PUBBURI_PG_DB_NAME")); assertTrue(Set.of("INSERT", "UPDATE", "DELETE").contains(operation));
        String result = operation.equals("DELETE") ? "OLD" : "NEW";
        sql.execute("CREATE FUNCTION pubburi.ci_jpa_comment_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF " + condition + " THEN RAISE EXCEPTION 'authored comment failure'; END IF; RETURN " + result + "; END $$");
        try { sql.execute("CREATE TRIGGER ci_jpa_comment_failure AFTER " + operation + " ON pubburi.t_comment FOR EACH ROW EXECUTE FUNCTION pubburi.ci_jpa_comment_failure()"); }
        catch (Throwable error) { sql.execute("DROP FUNCTION pubburi.ci_jpa_comment_failure()"); throw error; }
        return () -> { try { sql.execute("DROP TRIGGER ci_jpa_comment_failure ON pubburi.t_comment"); } finally { sql.execute("DROP FUNCTION pubburi.ci_jpa_comment_failure()"); } };
    }
    @Test void failedIdentityInsertLeavesNoCommentOrUnrelatedBusinessChanges() throws Exception { failed("INSERT"); }
    @Test void failedUpdateRetainsTheOriginalRatingBodyAndOwnership() throws Exception { failed("UPDATE"); }
    @Test void failedDeleteRetainsTheCommentAndItsParents() throws Exception { failed("DELETE"); }
    private void failed(String operation) throws Exception {
        var sql = control(); var admin = loggedIn("ADMIN"); String user = join(); int product = create(admin, ownName());
        String text = "ci_jpa_comment_" + UUID.randomUUID().toString().replace("-", "");
        try {
            var owner = login(user, password); var createBody = Map.of("productId", product, "rating", 3.7, "comment", text); int id = 0;
            if (!operation.equals("INSERT")) { var created = request(owner, "POST", "/api/comments", createBody); assertEquals(201, created.status()); id = created.body().path("data").path("id").intValue(); }
            var rows = sql.queryForList("SELECT * FROM pubburi.t_comment WHERE user_id=? ORDER BY id", user);
            var account = accountState(sql, user); var producer = state(sql, product); var identity = sourceIdentity(sql);
            String condition = operation.equals("INSERT") ? "NEW.user_id='" + user + "' AND NEW.product_id=" + product + " AND NEW.comment='" + text + "'"
                : (operation.equals("DELETE") ? "OLD" : "NEW") + ".id=" + id;
            String method = operation.equals("INSERT") ? "POST" : operation.equals("UPDATE") ? "PUT" : "DELETE";
            String path = "/api/comments" + (id == 0 ? "" : "/" + id);
            Object body = operation.equals("INSERT") ? createBody : operation.equals("UPDATE") ? Map.of("rating", 1.25, "comment", text + "_changed") : null;
            try (var fault = trigger(sql, operation, condition)) {
                assertEquals(500, request(owner, method, path, body).status());
                assertEquals(rows, sql.queryForList("SELECT * FROM pubburi.t_comment WHERE user_id=? ORDER BY id", user));
                assertEquals(account, accountState(sql, user)); assertEquals(producer, state(sql, product)); assertEquals(identity, sourceIdentity(sql));
            }
            assertEquals(operation.equals("INSERT") ? 201 : 200, request(owner, method, path, body).status());
        } finally { clean(sql, user, product, List.of()); }
    }
    @Test void failedOpinionInsertRollsBackReviewAndFailedReplacementRetainsTheWholeOriginal() throws Exception {
        var sql=control();var admin=loggedIn("ADMIN");String user=join();int product=create(admin,ownName());
        try {
            var owner=login(user,password);var first=Map.of("productId",product,"rating",3.75,"comment","before","tasteTags",List.of("before"));
            int id=request(owner,"POST","/api/comments",first).body().path("data").path("id").intValue();
            sql.execute("CREATE FUNCTION pubburi.ci_jpa_opinion_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.tag='authored-fail' THEN RAISE EXCEPTION 'authored opinion failure'; END IF; RETURN NEW; END $$");
            try {
                sql.execute("CREATE TRIGGER ci_jpa_opinion_failure AFTER INSERT ON pubburi.t_comment_taste_tag FOR EACH ROW EXECUTE FUNCTION pubburi.ci_jpa_opinion_failure()");
                assertEquals(500,request(owner,"POST","/api/comments",Map.of("productId",product,"rating",1.25,"comment","must roll back","tasteTags",List.of("authored-fail"))).status());
                assertEquals(1,sql.queryForObject("SELECT count(*) FROM pubburi.t_comment WHERE product_id=?",Integer.class,product));
                assertEquals(500,request(owner,"PUT","/api/comments/"+id,Map.of("rating",1.25,"comment","must roll back","tasteTags",List.of("authored-fail"))).status());
                assertEquals("before",sql.queryForObject("SELECT comment FROM pubburi.t_comment WHERE id=?",String.class,id));assertEquals(3.75f,sql.queryForObject("SELECT rating FROM pubburi.t_comment WHERE id=?",Float.class,id));
                assertEquals(List.of("before"),sql.queryForList("SELECT tag FROM pubburi.t_comment_taste_tag WHERE comment_id=?",String.class,id));
            } finally {sql.execute("DROP TRIGGER IF EXISTS ci_jpa_opinion_failure ON pubburi.t_comment_taste_tag");sql.execute("DROP FUNCTION pubburi.ci_jpa_opinion_failure()");}
        } finally {clean(sql,user,product,List.of());}
    }

}
