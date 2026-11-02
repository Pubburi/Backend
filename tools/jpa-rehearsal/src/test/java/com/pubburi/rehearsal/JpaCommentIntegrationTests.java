package com.pubburi.rehearsal;

import static org.junit.jupiter.api.Assertions.*;

import com.pubburi.pub.model.dao.*;
import com.pubburi.pub.model.dto.Comment;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "PUBBURI_JPA_INTEGRATION", matches = "1")
class JpaCommentIntegrationTests extends JpaAccountTestSupport {
    @Autowired CommentDao comments;
    @Autowired UserDao users;
    @Autowired ProductDao products;
    @Autowired DataSource dataSource;
    @Autowired PlatformTransactionManager transactions;
    Map<String, Object> review(int product, float rating, String body) { return Map.of("productId", product, "rating", rating, "comment", body); }
    String path(int product) { return "/api/products/" + product + "/comments"; }

    @Test void fractionalCreateAndBodyEditPreserveWireFieldsOwnershipAndProducerState() throws Exception {
        var sql = control(); var admin = loggedIn("ADMIN"); String user = join(); int product = create(admin, ownName());
        try {
            var owner = login(user, password); var before = state(sql, product); String text = "  authored\n\"review\"\\body  ";
            assertEquals(401, request(client(), "POST", "/api/comments", review(product, 3.7f, text)).status());
            var body = new HashMap<>(review(product, 3.7f, text)); body.put("userId", required("PUBBURI_JPA_ADMIN_ID"));
            var created = request(owner, "POST", "/api/comments", body); assertEquals(201, created.status(), created.body().toString());
            var value = created.body().path("data"); int id = value.path("id").intValue();
            assertTrue(id >= Integer.parseInt(required("PUBBURI_JPA_EXPECTED_COMMENT_NEXT")));
            assertEquals(Set.of("id", "userId", "productId", "rating", "comment"), keys(value));
            assertEquals(user, value.path("userId").textValue()); assertEquals(product, value.path("productId").intValue()); assertEquals(text, value.path("comment").textValue());
            assertEquals(Float.floatToIntBits(3.7f), Float.floatToIntBits(value.path("rating").floatValue()));
            assertEquals(Float.floatToIntBits(3.7f), Float.floatToIntBits(sql.queryForObject("SELECT rating FROM pubburi.t_comment WHERE id=?", Float.class, id)));
            for (var invalid : List.of(review(product, 0.4f, "invalid"), review(product, 5.1f, "invalid"), review(product, 1.25f, " "), review(product, 1.25f, "x".repeat(201))))
                assertEquals(400, request(owner, "POST", "/api/comments", invalid).status());
            var nullBody = new HashMap<String, Object>(); nullBody.put("rating", 2.5); nullBody.put("comment", null);
            assertEquals(400, request(owner, "PUT", "/api/comments/" + id, nullBody).status());
            assertEquals(text, comments.select(id).getComment());
            var changed = request(owner, "PUT", "/api/comments/" + id, Map.of("rating", 1.25, "comment", "가".repeat(200)));
            assertEquals(200, changed.status()); assertEquals(1.25f, changed.body().path("data").path("rating").floatValue());
            assertEquals("가".repeat(200), comments.select(id).getComment());
            assertEquals(before, state(sql, product));
            assertEquals(401, request(client(), "GET", "/api/admin/comments", null).status());
            assertEquals(403, request(owner, "GET", "/api/admin/comments", null).status());
            var page = request(admin, "GET", "/api/admin/comments?size=1", null); assertEquals(200, page.status());
            assertEquals(id, page.body().path("data").path("items").get(0).path("id").intValue());
        } finally { clean(sql, user, product, List.of()); }
    }

    @Test void publicPagesKeepDescIdsNullableHistoryAndMissingProductEmptyVersusFkConflict() throws Exception {
        var sql = control(); var admin = loggedIn("ADMIN"); String user = join(); int product = create(admin, ownName());
        try {
            var owner = login(user, password); var ids = new ArrayList<Integer>();
            for (int i = 1; i <= 3; i++) {
                var reply = request(owner, "POST", "/api/comments", review(product, i + .25f, "authored " + i)); assertEquals(201, reply.status()); ids.add(reply.body().path("data").path("id").intValue());
            }
            int historical = sql.queryForObject("INSERT INTO pubburi.t_comment(user_id,product_id,rating,comment) VALUES(?,?,4.25,NULL) RETURNING id", Integer.class, user, product);
            var first = request(client(), "GET", path(product) + "?size=2", null); assertEquals(200, first.status()); var page = first.body().path("data");
            assertEquals(Set.of("items", "page", "size", "total", "totalPages", "hasNext"), keys(page)); assertEquals(4, page.path("total").intValue());
            assertEquals(historical, page.path("items").get(0).path("id").intValue()); assertTrue(page.path("items").get(0).path("comment").isNull());
            assertEquals(ids.get(2).intValue(), page.path("items").get(1).path("id").intValue());
            var second = request(client(), "GET", path(product) + "?size=2&page=2", null).body().path("data");
            assertEquals(ids.get(1).intValue(), second.path("items").get(0).path("id").intValue()); assertFalse(second.path("hasNext").booleanValue());
            assertEquals(200, request(client(), "GET", path(Integer.MAX_VALUE), null).status());
            assertEquals(0, request(client(), "GET", path(Integer.MAX_VALUE), null).body().path("data").path("total").intValue());
            assertEquals(0, request(client(), "GET", path(0) + "?page=0&size=999", null).body().path("data").path("total").intValue());
            assertEquals(409, request(owner, "POST", "/api/comments", review(Integer.MAX_VALUE, 3.75f, "absent FK")).status());
            assertEquals(4, comments.countByProductId(product)); assertEquals(404, request(client(), "PUT", "/api/comments/2147483647", Map.of("rating", 2.5, "comment", "missing")).status());
        } finally { clean(sql, user, product, List.of()); }
    }

    @Test void ownerAdminAndCurrentCredentialChecksRemainSeparateFromParentCascades() throws Exception {
        var sql = control(); var admin = loggedIn("ADMIN"); String user = join(); String other = join(); int product = create(admin, ownName());
        try {
            var owner = login(user, password); var stranger = login(other, password);
            var created = request(owner, "POST", "/api/comments", review(product, .5f, "owned")); assertEquals(201, created.status()); int id = created.body().path("data").path("id").intValue();
            assertEquals(403, request(stranger, "PUT", "/api/comments/" + id, Map.of("rating", 2.5, "comment", "forbidden")).status());
            assertEquals(403, request(stranger, "DELETE", "/api/comments/" + id, null).status());
            var edit = request(admin, "PUT", "/api/comments/" + id, Map.of("rating", 4.25, "comment", "moderated", "userId", other, "productId", Integer.MAX_VALUE));
            assertEquals(200, edit.status()); assertEquals(user, edit.body().path("data").path("userId").textValue()); assertEquals(product, edit.body().path("data").path("productId").intValue());
            assertEquals(200, request(admin, "PUT", "/api/admin/users/" + user, Map.of("role", "ADMIN")).status());
            assertEquals(200, request(owner, "GET", "/api/admin/comments", null).status());
            assertEquals(200, request(admin, "PUT", "/api/admin/users/" + user, Map.of("role", "USER", "pass", "Authored-comment-fresh")).status());
            assertEquals(401, request(owner, "PUT", "/api/comments/" + id, Map.of("rating", 2.5, "comment", "expired")).status());
            var fresh = login(user, "Authored-comment-fresh"); assertEquals(403, request(fresh, "GET", "/api/admin/comments", null).status());
            assertEquals(200, request(admin, "DELETE", "/api/admin/users/" + user, null).status()); assertNull(comments.select(id));
            assertEquals(401, request(fresh, "POST", "/api/comments", review(product, 1.25f, "expired user")).status());
            var strangerReview = request(stranger, "POST", "/api/comments", review(product, 2.75f, "product cascade")); assertEquals(201, strangerReview.status());
            int cascadeId = strangerReview.body().path("data").path("id").intValue();
            assertEquals(200, request(admin, "DELETE", "/api/admin/products/" + product, null).status()); assertNull(comments.select(cascadeId));
            assertEquals(0, request(client(), "GET", path(product), null).body().path("data").path("total").intValue());
        } finally { clean(sql, user, product, List.of()); clean(sql, other, null, List.of()); }
    }

    @Test void competingWritersKeepCompletePairsAndCompetingDeletesReturnOneSuccess() throws Exception {
        var sql = control(); var admin = loggedIn("ADMIN"); String user = join(); int product = create(admin, ownName()); var executor = Executors.newFixedThreadPool(2);
        try {
            var owner = login(user, password); int id = request(owner, "POST", "/api/comments", review(product, 2.5f, "before")).body().path("data").path("id").intValue();
            var producer = state(sql, product); var start = new CountDownLatch(1);
            var first = executor.submit(() -> { start.await(); return request(owner, "PUT", "/api/comments/" + id, Map.of("rating", 1.25, "comment", "first")); });
            var second = executor.submit(() -> { start.await(); return request(admin, "PUT", "/api/comments/" + id, Map.of("rating", 4.75, "comment", "second")); });
            start.countDown(); assertEquals(200, first.get(12, TimeUnit.SECONDS).status()); assertEquals(200, second.get(12, TimeUnit.SECONDS).status());
            var row = comments.select(id); assertTrue(Set.of("1.25|first", "4.75|second").contains(row.getRating() + "|" + row.getComment()));
            assertEquals(user, row.getUserId()); assertEquals(product, row.getProductId());
            var next = new CountDownLatch(1); var otherAdmin = loggedIn("ADMIN");
            var deleteOne = executor.submit(() -> { next.await(); return request(admin, "DELETE", "/api/comments/" + id, null).status(); });
            var deleteTwo = executor.submit(() -> { next.await(); return request(otherAdmin, "DELETE", "/api/comments/" + id, null).status(); });
            next.countDown(); assertEquals(Set.of(200, 404), Set.of(deleteOne.get(12, TimeUnit.SECONDS), deleteTwo.get(12, TimeUnit.SECONDS)));
            assertEquals(0, comments.update(row)); assertNull(comments.select(id)); assertEquals(producer, state(sql, product));
        } finally { executor.shutdownNow(); assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS)); clean(sql, user, product, List.of()); }
    }

    @Test void parentFullLocksBlockFkInsertsAndRuntimeCannotRebindOwnersOrResetIdentity() throws Exception {
        var sql = control(); var admin = loggedIn("ADMIN"); String user = join(); int product = create(admin, ownName()); var executor = Executors.newSingleThreadExecutor();
        try {
            for (String parent : List.of("user", "product")) new TransactionTemplate(transactions).execute(status -> {
                if (parent.equals("user")) assertNotNull(users.selectByIdForUpdate(user)); else assertNotNull(products.selectByIdForUpdate(product));
                var result = executor.submit(() -> {
                    try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
                        connection.setAutoCommit(false); statement.execute("SET LOCAL lock_timeout='250ms'");
                        try (var insert = connection.prepareStatement("INSERT INTO pubburi.t_comment(user_id,product_id,rating,comment) VALUES(?,?,3.7,'authored lock')")) {
                            insert.setString(1, user); insert.setInt(2, product);
                            try { insert.executeUpdate(); return "unexpected_success"; } catch (SQLException error) { return error.getSQLState(); }
                            finally { connection.rollback(); }
                        }
                    }
                });
                try { assertEquals("55P03", result.get(3, TimeUnit.SECONDS)); } catch (Exception error) { throw new IllegalStateException("owned comment FK experiment failed", error); }
                return null;
            });
            for (String denied : List.of("UPDATE pubburi.t_comment SET id=id WHERE false", "UPDATE pubburi.t_comment SET user_id=user_id WHERE false", "UPDATE pubburi.t_comment SET product_id=product_id WHERE false", "TRUNCATE pubburi.t_comment", "SELECT setval('pubburi.t_comment_id_seq',1)")) {
                var error = assertThrows(DataAccessException.class, () -> runtime.execute(denied)); assertEquals("42501", ((SQLException) error.getMostSpecificCause()).getSQLState());
            }
        } finally { executor.shutdownNow(); assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS)); clean(sql, user, product, List.of()); }
    }
}
