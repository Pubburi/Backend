package com.pubburi.rehearsal;

import static org.junit.jupiter.api.Assertions.*;

import com.pubburi.pub.model.dao.FavoriteDao;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "server.port=0")
@Import(JpaFavoriteIntegrationTests.Probes.class)
@EnabledIfEnvironmentVariable(named = "PUBBURI_JPA_INTEGRATION", matches = "1")
class JpaFavoriteIntegrationTests extends JpaAccountTestSupport {
    static final String PATH = "/api/users/me/favorites";
    @Autowired FavoriteDao favorites;
    @Autowired PlatformTransactionManager transactions;
    @Autowired Gate gate;
    List<Integer> ids(com.fasterxml.jackson.databind.JsonNode page) { var ids = new ArrayList<Integer>(); page.path("items").forEach(p -> ids.add(p.path("id").intValue())); return ids; }
    List<Map<String,Object>> rows(org.springframework.jdbc.core.JdbcTemplate sql, String user) { return sql.queryForList("SELECT * FROM pubburi.t_user_favorite WHERE user_id=? ORDER BY created_at DESC,product_id DESC", user); }

    @Test void privateOwnerStateAndPagesKeepCurrentProductShapeAndDoNotMutateBusinessCounters() throws Exception {
        var sql = control(); var admin = loggedIn("ADMIN"); String user = join(), other = join(); int product = create(admin, ownName());
        try {
            var owner = login(user, password); var stranger = login(other, password); var before = state(sql, product); var account = accountState(sql, other);
            for (String method : List.of("GET", "PUT", "DELETE")) assertEquals(401, request(client(), method, PATH + "/" + product, null).status());
            assertFalse(request(owner, "GET", PATH + "/" + product, null).body().path("data").path("favorited").booleanValue());
            assertEquals(200, request(owner, "PUT", PATH + "/" + product + "?userId=" + other, Map.of("userId", other)).status());
            var value = request(owner, "GET", PATH + "/" + product, null).body().path("data"); assertEquals(Set.of("productId", "favorited"), keys(value)); assertTrue(value.path("favorited").booleanValue());
            assertFalse(request(stranger, "GET", PATH + "/" + product, null).body().path("data").path("favorited").booleanValue());
            assertFalse(request(admin, "GET", PATH + "/" + product, null).body().path("data").path("favorited").booleanValue());
            var page = request(owner, "GET", PATH + "?userId=" + other, null); assertEquals(200, page.status());
            assertEquals(List.of(product), ids(page.body().path("data"))); publicProduct(page.body().path("data").path("items").get(0));
            assertEquals(before, state(sql, product)); assertEquals(account, accountState(sql, other));
            assertEquals(0, sql.queryForObject("SELECT stamps FROM pubburi.t_user WHERE id=?", Integer.class, user));
            assertEquals(405, request(owner, "POST", PATH + "/" + product, null).status());
        } finally { clean(sql, user, product, List.of()); clean(sql, other, null, List.of()); }
    }
    void publicProduct(com.fasterxml.jackson.databind.JsonNode product) { assertEquals(Set.of("id","name","type","price","img","abv","orderCount"), keys(product)); }

    @Test void repeatedPutKeepsFirstTimestampAndLatestCatalogAndTieOrderRemainDeterministic() throws Exception {
        var sql = control(); var admin = loggedIn("ADMIN"); String user = join(); int first = create(admin, ownName()), second = create(admin, ownName());
        try {
            var owner = login(user, password);
            assertEquals(200, request(owner, "PUT", PATH + "/" + first, null).status()); var original = rows(sql, user);
            assertEquals(200, request(owner, "PUT", PATH + "/" + first, null).status()); assertEquals(original, rows(sql, user));
            assertEquals(200, request(owner, "PUT", PATH + "/" + second, null).status());
            assertEquals(List.of(second, first), ids(request(owner, "GET", PATH, null).body().path("data")));
            sql.update("UPDATE pubburi.t_user_favorite SET created_at=TIMESTAMPTZ '2026-01-01 00:00:00+00' WHERE user_id=?", user);
            assertEquals(List.of(second, first), ids(request(owner, "GET", PATH, null).body().path("data")));
            assertEquals(200, request(admin, "PUT", "/api/admin/products/" + first, product(ownName(), 9900)).status());
            var page = request(owner, "GET", PATH, null).body().path("data"); assertEquals(9900, page.path("items").get(1).path("price").intValue());
            assertEquals(200, request(owner, "DELETE", PATH + "/" + first, null).status()); assertEquals(200, request(owner, "PUT", PATH + "/" + first, null).status());
            assertEquals(List.of(first, second), ids(request(owner, "GET", PATH, null).body().path("data")));
        } finally { clean(sql, user, first, List.of()); sql.update("DELETE FROM pubburi.t_product WHERE id=? AND name LIKE 'ci_jpa_write_%'", second); }
    }

    @Test void invalidNumbersMissingProductAndValidEmptyPageKeepAbsenceDifferentFromExpiry() throws Exception {
        var sql = control(); var admin = loggedIn("ADMIN"); String user = join(); int product = create(admin, ownName());
        try {
            var owner = login(user, password); assertEquals(200, request(owner, "PUT", PATH + "/" + product, null).status()); var before = rows(sql, user);
            for (String id : List.of("0", "-1", "1.5", "1e2", "2147483648")) for (String method : List.of("GET", "PUT", "DELETE")) assertEquals(400, request(owner, method, PATH + "/" + id, null).status());
            for (String query : List.of("?page=1.5", "?size=2147483648", "?page=2147483647&size=100")) assertEquals(400, request(owner, "GET", PATH + query, null).status());
            assertEquals(404, request(owner, "PUT", PATH + "/2147483647", null).status());
            assertFalse(request(owner, "GET", PATH + "/2147483647", null).body().path("data").path("favorited").booleanValue());
            assertEquals(200, request(owner, "DELETE", PATH + "/2147483647", null).status()); assertEquals(before, rows(sql, user));
            var empty = request(owner, "GET", PATH + "?page=2&size=1", null).body().path("data"); assertEquals(1, empty.path("total").intValue()); assertEquals(0, empty.path("items").size());
            var normalized = request(owner, "GET", PATH + "?page=0&size=999", null).body().path("data"); assertEquals(1, normalized.path("page").intValue()); assertEquals(100, normalized.path("size").intValue());
        } finally { clean(sql, user, product, List.of()); }
    }

    @Test void parentDeletionAndSameIdRejoinNeverRestorePrivateMembershipOrAlterOrderHistory() throws Exception {
        var sql = control(); var admin = loggedIn("ADMIN"); String user = join(); int product = create(admin, ownName()); var orders = new ArrayList<Integer>();
        try {
            var owner = login(user, password); var dormant = login(user, password);
            assertEquals(200, request(owner, "PUT", PATH + "/" + product, null).status());
            var order = request(owner, "POST", "/api/orders", Map.of("details", List.of(Map.of("productId", product, "quantity", 2)))); assertEquals(201, order.status()); orders.add(order.body().path("data").path("oId").intValue());
            assertEquals(200, request(admin, "DELETE", "/api/admin/users/" + user, null).status()); assertEquals(0, rows(sql, user).size());
            assertEquals(201, request(client(), "POST", "/api/users", registration(user)).status()); assertEquals(401, request(dormant, "GET", PATH, null).status());
            var fresh = login(user, password); assertEquals(0, request(fresh, "GET", PATH, null).body().path("data").path("total").intValue());
            assertFalse(request(fresh, "GET", PATH + "/" + product, null).body().path("data").path("favorited").booleanValue());
            var history = request(admin, "GET", "/api/orders/" + orders.get(0), null).body().path("data"); assertTrue(history.path("userId").isNull()); assertEquals(2400, history.path("totalPrice").intValue());
            assertEquals(200, request(fresh, "PUT", PATH + "/" + product, null).status()); assertEquals(200, request(admin, "DELETE", "/api/admin/products/" + product, null).status());
            assertFalse(request(fresh, "GET", PATH + "/" + product, null).body().path("data").path("favorited").booleanValue()); assertEquals(200, request(fresh, "DELETE", PATH + "/" + product, null).status());
        } finally { clean(sql, user, product, orders); }
    }

    @Test void rrPageAndQueuedOldEpochKeepTheOriginalAccountSnapshot() throws Exception {
        var sql = control(); var admin = loggedIn("ADMIN"); String user = join(); int first = create(admin, ownName()), second = create(admin, ownName()); var executor = Executors.newSingleThreadExecutor();
        try {
            var owner = login(user, password); assertEquals(200, request(owner, "PUT", PATH + "/" + first, null).status());
            try (var pause = gate.arm("items")) {
                var reading = executor.submit(() -> request(owner, "GET", PATH, null)); assertTrue(pause.entered.await(4, TimeUnit.SECONDS));
                sql.update("INSERT INTO pubburi.t_user_favorite(user_id,product_id,created_at) VALUES(?,?,statement_timestamp())", user, second);
                pause.release.countDown(); var snapshot = reading.get(6, TimeUnit.SECONDS); assertEquals(200, snapshot.status()); assertEquals(1, snapshot.body().path("data").path("total").intValue()); assertEquals(List.of(first), ids(snapshot.body().path("data")));
            }
            assertEquals(2, request(owner, "GET", PATH, null).body().path("data").path("total").intValue());
            assertEquals(200, request(owner, "DELETE", PATH + "/" + second, null).status());
            try (var pause = gate.arm("userLock")) {
                var writing = executor.submit(() -> request(owner, "PUT", PATH + "/" + second, null)); assertTrue(pause.entered.await(4, TimeUnit.SECONDS));
                assertEquals(200, request(admin, "PUT", "/api/admin/users/" + user, Map.of("pass", "Authored-rotated-favorite")).status());
                pause.release.countDown(); assertEquals(401, writing.get(6, TimeUnit.SECONDS).status());
            }
            var fresh = login(user, "Authored-rotated-favorite"); assertEquals(List.of(first), ids(request(fresh, "GET", PATH, null).body().path("data")));
        } finally { executor.shutdownNow(); assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS)); clean(sql, user, first, List.of()); sql.update("DELETE FROM pubburi.t_product WHERE id=? AND name LIKE 'ci_jpa_write_%'", second); }
    }

    @Test void statementTimeAndConcurrentIdempotencyWorkWithoutAnyUpdatePermission() throws Exception {
        var sql = control(); var admin = loggedIn("ADMIN"); String user = join(); int product = create(admin, ownName()); var executor = Executors.newFixedThreadPool(2);
        try {
            new TransactionTemplate(transactions).execute(status -> {
                runtime.queryForObject("SELECT CURRENT_TIMESTAMP", java.sql.Timestamp.class); sql.execute("SELECT pg_sleep(0.2)");
                var lower = sql.queryForObject("SELECT statement_timestamp()", java.sql.Timestamp.class).toInstant(); assertEquals(1, favorites.ensurePresent(user, product));
                var stored = runtime.queryForObject("SELECT created_at FROM pubburi.t_user_favorite WHERE user_id=? AND product_id=?", java.sql.Timestamp.class, user, product).toInstant(); assertFalse(stored.isBefore(lower)); return null;
            });
            var before = rows(sql, user); var one = login(user, password); var two = login(user, password); var start = new CountDownLatch(1);
            var putOne = executor.submit(() -> { start.await(); return request(one, "PUT", PATH + "/" + product, null).status(); });
            var putTwo = executor.submit(() -> { start.await(); return request(two, "PUT", PATH + "/" + product, null).status(); });
            start.countDown(); assertEquals(200, putOne.get(10, TimeUnit.SECONDS)); assertEquals(200, putTwo.get(10, TimeUnit.SECONDS)); assertEquals(before, rows(sql, user));
            var next = new CountDownLatch(1);
            var deleteOne = executor.submit(() -> { next.await(); return request(one, "DELETE", PATH + "/" + product, null).status(); });
            var deleteTwo = executor.submit(() -> { next.await(); return request(two, "DELETE", PATH + "/" + product, null).status(); });
            next.countDown(); assertEquals(200, deleteOne.get(10, TimeUnit.SECONDS)); assertEquals(200, deleteTwo.get(10, TimeUnit.SECONDS)); assertEquals(0, rows(sql, user).size());
            for (String column : List.of("user_id", "product_id", "created_at")) assertThrows(DataAccessException.class, () -> runtime.execute("UPDATE pubburi.t_user_favorite SET " + column + "=" + column + " WHERE false"));
            assertThrows(DataAccessException.class, () -> runtime.execute("TRUNCATE pubburi.t_user_favorite"));
        } finally { executor.shutdownNow(); assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS)); clean(sql, user, product, List.of()); }
    }

    static final class Pause implements AutoCloseable {
        final String kind; final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        Pause(String kind) { this.kind = kind; } @Override public void close() { release.countDown(); }
    }
    static final class Gate implements StatementInspector {
        final AtomicReference<Pause> armed = new AtomicReference<>();
        Pause arm(String kind) { var pause = new Pause(kind); assertTrue(armed.compareAndSet(null, pause)); return pause; }
        @Override public String inspect(String sql) {
            var pause = armed.get(); String lower = sql.toLowerCase(Locale.ROOT);
            boolean match = pause != null && (pause.kind.equals("items") ? lower.startsWith("select ") && lower.contains(" from pubburi.t_user_favorite ") && lower.contains("order by") : lower.startsWith("select id from pubburi.t_user ") && lower.contains("for update"));
            if (match && armed.compareAndSet(pause, null)) { pause.entered.countDown(); try { if (!pause.release.await(6, TimeUnit.SECONDS)) throw new IllegalStateException("owned favorite gate deadline"); } catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); } }
            return sql;
        }
    }
    @TestConfiguration(proxyBeanMethods = false) static class Probes {
        @Bean Gate favoriteGate() { return new Gate(); }
        @Bean HibernatePropertiesCustomizer favoriteProbe(Gate gate) { return values -> values.put("hibernate.session_factory.statement_inspector", gate); }
    }
}
