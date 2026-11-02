package com.pubburi.rehearsal;

import static org.junit.jupiter.api.Assertions.*;

import com.pubburi.pub.model.dao.OrderDao;
import com.pubburi.pub.model.service.OrderService;
import jakarta.persistence.EntityManagerFactory;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "PUBBURI_JPA_INTEGRATION", matches = "1")
class JpaOrderIntegrationTests extends JpaOrderTestSupport {
    @Autowired OrderDao orders;
    @Autowired OrderService service;
    @Autowired EntityManagerFactory entities;
    @Autowired PlatformTransactionManager transactions;

    @Test void existingHttpCreatesNormalizedSnapshotsAndRetainsHistoryAfterProductAndUserDeletion() throws Exception {
        var sql = control(); var admin = loggedIn("ADMIN"); String user = addUser(); int product = create(admin, ownName());
        var buyer = loginUser(user); int id = 0;
        try {
            var body = Map.of("details", List.of(Map.of("productId", product, "quantity", 1), Map.of("productId", product, "quantity", 2)));
            assertEquals(401, request(client(), "POST", "/api/orders", body).status());
            assertEquals(403, request(loggedIn("USER"), "POST", "/api/orders", Map.of("userId", user, "details", body.get("details"))).status());
            var created = request(buyer, "POST", "/api/orders", body); assertEquals(201, created.status(), created.body().toString());
            var value = created.body().path("data"); id = value.path("oId").intValue();
            assertEquals(Set.of("oId", "userId", "orderType", "orderTable", "completed", "orderTime", "details", "totalPrice", "priceBasis"), keys(value));
            assertEquals("TAKEOUT", value.path("orderType").textValue()); assertEquals("N", value.path("completed").textValue());
            assertEquals("ORDER_SNAPSHOT", value.path("priceBasis").textValue()); assertEquals(3600, value.path("totalPrice").intValue());
            assertFalse(value.path("orderTime").isNull()); assertEquals(1, value.path("details").size());
            assertEquals(3, value.path("details").get(0).path("quantity").intValue());
            assertEquals(1200, value.path("details").get(0).path("unitPrice").intValue());
            assertEquals(3, sql.queryForObject("SELECT stamps FROM pubburi.t_user WHERE id=?", Integer.class, user));
            assertEquals(3, sql.queryForObject("SELECT quantity FROM pubburi.t_stamp WHERE order_id=?", Integer.class, id));
            assertEquals(3, sql.queryForObject("SELECT order_count FROM pubburi.t_product WHERE id=?", Integer.class, product));
            assertEquals(2, events(sql, product).size()); assertEquals("PENDING", events(sql, product).get(1).get("status"));
            assertEquals(3, json.readTree((String) events(sql, product).get(1).get("payload")).path("document").path("orderCount").intValue());
            assertEquals(403, request(loggedIn("USER"), "GET", "/api/orders/" + id, null).status());
            assertEquals(403, request(buyer, "GET", "/api/admin/orders", null).status());
            assertEquals(403, request(buyer, "PATCH", "/api/admin/orders/" + id + "/complete", null).status());
            assertEquals(200, request(admin, "PUT", "/api/admin/products/" + product, product(ownName(), 9900)).status());
            assertEquals(value, request(buyer, "GET", "/api/orders/" + id, null).body().path("data"));
            assertEquals(200, request(admin, "DELETE", "/api/admin/products/" + product, null).status());
            var deletedProduct = request(buyer, "GET", "/api/orders/" + id, null).body().path("data");
            assertTrue(deletedProduct.path("details").get(0).path("productId").isNull());
            assertEquals(3600, deletedProduct.path("totalPrice").intValue());
            assertEquals(value.path("details").get(0).path("name"), deletedProduct.path("details").get(0).path("name"));
            assertEquals(1, users.removeUser(user));
            assertEquals(401, request(buyer, "GET", "/api/orders/" + id, null).status());
            var orphan = request(admin, "GET", "/api/orders/" + id, null).body().path("data");
            assertTrue(orphan.path("userId").isNull()); assertTrue(orphan.path("orderTable").isNull());
            assertEquals(3600, orphan.path("totalPrice").intValue());
            assertEquals(1, users.addUser(new com.pubburi.pub.model.dto.User(user, "Authored replacement", password, 0, "USER")));
            assertEquals(403, request(loginUser(user), "GET", "/api/orders/" + id, null).status());
            assertEquals(0, request(loginUser(user), "GET", "/api/users/" + user + "/orders", null).body().path("data").path("total").intValue());
            assertEquals(200, request(admin, "DELETE", "/api/admin/orders/" + id, null).status()); id = 0;
        } finally {
            if (id != 0) sql.update("DELETE FROM pubburi.t_order WHERE o_id=?", id);
            clean(sql, user, product);
        }
    }

    @Test void pageUsesOneBatchForDetailsAndCompletionDeletionPreserveLegacyEffects() throws Exception {
        var sql = control(); var admin = loggedIn("ADMIN"); String user = addUser(); int product = create(admin, ownName()); var buyer = loginUser(user);
        try {
            int first = request(buyer, "POST", "/api/orders", order(product, 1)).body().path("data").path("oId").intValue();
            var secondReply = request(buyer, "POST", "/api/orders", order(product, 2)); assertEquals(201, secondReply.status());
            int second = secondReply.body().path("data").path("oId").intValue();
            var stats = entities.unwrap(SessionFactoryImplementor.class).getStatistics(); boolean enabled = stats.isStatisticsEnabled(); stats.setStatisticsEnabled(true);
            long before = stats.getPrepareStatementCount();
            com.pubburi.pub.controller.api.PageResponse<com.pubburi.pub.model.dto.OrderInfo> page;
            try {
                page = service.getOrdersByUserId(user, com.pubburi.pub.controller.api.PageCriteria.of(1, 10, 10));
                assertEquals(3, stats.getPrepareStatementCount() - before); // page + one IN query + count
            } finally { stats.setStatisticsEnabled(enabled); }
            assertEquals(List.of(second, first), page.items().stream().map(o -> o.getoId()).toList());
            assertTrue(page.items().stream().allMatch(o -> o.getDetails().size() == 1));
            var http = request(buyer, "GET", "/api/users/" + user + "/orders?size=1", null).body().path("data");
            assertEquals(2, http.path("total").intValue()); assertEquals(second, http.path("items").get(0).path("oId").intValue());
            assertEquals(200, request(admin, "GET", "/api/admin/orders?size=1", null).status());
            assertEquals(200, request(admin, "PATCH", "/api/admin/orders/" + second + "/complete", null).status());
            assertEquals("Y", request(buyer, "GET", "/api/orders/" + second, null).body().path("data").path("completed").textValue());
            assertEquals(200, request(admin, "DELETE", "/api/admin/orders/" + second, null).status());
            assertEquals(404, request(buyer, "GET", "/api/orders/" + second, null).status());
            assertEquals(404, request(admin, "DELETE", "/api/admin/orders/" + second, null).status());
            assertEquals(0, sql.queryForObject("SELECT count(*) FROM pubburi.t_order_detail WHERE order_id=?", Integer.class, second));
            assertEquals(0, sql.queryForObject("SELECT count(*) FROM pubburi.t_stamp WHERE order_id=?", Integer.class, second));
            assertEquals(3, sql.queryForObject("SELECT stamps FROM pubburi.t_user WHERE id=?", Integer.class, user));
            assertEquals(3, sql.queryForObject("SELECT order_count FROM pubburi.t_product WHERE id=?", Integer.class, product));
        } finally { clean(sql, user, product); }
    }

    @Test void concurrentOrdersKeepAtomicCountersAndProducerVersionsAndInvalidOrderRollsBack() throws Exception {
        var sql = control(); var admin = loggedIn("ADMIN"); String user = addUser(); int product = create(admin, ownName());
        var one = loginUser(user); var two = loginUser(user); var executor = Executors.newFixedThreadPool(2);
        try {
            var before = orderState(sql, user, product);
            var invalid = Map.of("details", List.of(Map.of("productId", product, "quantity", 1), Map.of("productId", Integer.MAX_VALUE, "quantity", 1)));
            assertEquals(400, request(one, "POST", "/api/orders", invalid).status());
            assertEquals(before, orderState(sql, user, product));
            assertEquals(400, request(one, "POST", "/api/orders", order(product, Integer.MAX_VALUE)).status());
            assertEquals(before, orderState(sql, user, product));
            var overflow = Map.of("details", List.of(Map.of("productId", product, "quantity", Integer.MAX_VALUE), Map.of("productId", product, "quantity", 1)));
            assertEquals(400, request(one, "POST", "/api/orders", overflow).status());
            assertEquals(before, orderState(sql, user, product));
            var start = new CountDownLatch(1);
            var first = executor.submit(() -> { start.await(); return request(one, "POST", "/api/orders", order(product, 2)); });
            var second = executor.submit(() -> { start.await(); return request(two, "POST", "/api/orders", order(product, 3)); });
            start.countDown(); assertEquals(201, first.get(12, TimeUnit.SECONDS).status()); assertEquals(201, second.get(12, TimeUnit.SECONDS).status());
            assertEquals(5, sql.queryForObject("SELECT stamps FROM pubburi.t_user WHERE id=?", Integer.class, user));
            assertEquals(5, sql.queryForObject("SELECT order_count FROM pubburi.t_product WHERE id=?", Integer.class, product));
            assertEquals(5, sql.queryForObject("SELECT sum(quantity) FROM pubburi.t_stamp WHERE user_id=?", Integer.class, user));
            var events = events(sql, product); assertEquals(List.of(1L, 2L, 3L), events.stream().map(e -> e.get("source_version")).toList());
            assertEquals(5, json.readTree((String) events.get(2).get("payload")).path("document").path("orderCount").intValue());
            assertTrue(events.stream().allMatch(e -> "PENDING".equals(e.get("status"))));
            int id = first.get().body().path("data").path("oId").intValue();
            var otherAdmin = loggedIn("ADMIN"); var deleteStart = new CountDownLatch(1);
            var deleteOne = executor.submit(() -> { deleteStart.await(); return request(admin, "DELETE", "/api/admin/orders/" + id, null).status(); });
            var deleteTwo = executor.submit(() -> { deleteStart.await(); return request(otherAdmin, "DELETE", "/api/admin/orders/" + id, null).status(); });
            deleteStart.countDown();
            assertEquals(Set.of(200, 404), Set.of(deleteOne.get(12, TimeUnit.SECONDS), deleteTwo.get(12, TimeUnit.SECONDS)));
        } finally { executor.shutdownNow(); assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS)); clean(sql, user, product); }
    }

    @Test void nullableImportedHistoryAndUtcMonthQueriesAndLeastPrivilegesArePreserved() throws Exception {
        var sql = control(); String user = addUser(); var ids = new ArrayList<Integer>();
        try {
            ids.add(sql.queryForObject("INSERT INTO pubburi.t_order(user_id,order_type,order_time,completed,total_price,price_basis) VALUES(?,'TAKEOUT',NULL,NULL,NULL,'LEGACY_ESTIMATE') RETURNING o_id", Integer.class, user));
            int recent = sql.queryForObject("INSERT INTO pubburi.t_order(user_id,order_type,order_time) VALUES(?,'TAKEOUT',CURRENT_TIMESTAMP-INTERVAL '15 days') RETURNING o_id", Integer.class, user); ids.add(recent);
            int older = sql.queryForObject("INSERT INTO pubburi.t_order(user_id,order_type,order_time) VALUES(?,'TAKEOUT',CURRENT_TIMESTAMP-INTERVAL '2 months') RETURNING o_id", Integer.class, user); ids.add(older);
            int old = sql.queryForObject("INSERT INTO pubburi.t_order(user_id,order_type,order_time) VALUES(?,'TAKEOUT',CURRENT_TIMESTAMP-INTERVAL '7 months') RETURNING o_id", Integer.class, user); ids.add(old);
            var absent = orders.selectById(ids.get(0)); assertNull(absent.getOrderTime()); assertNull(absent.getCompleted()); assertNull(absent.getTotalPrice());
            assertEquals(List.of(recent), orders.getLastMonthOrder(user).stream().map(o -> o.getoId()).toList());
            assertEquals(List.of(older, recent), orders.getLast6MonthOrder(user).stream().map(o -> o.getoId()).toList());
            Instant time = sql.queryForObject("SELECT order_time FROM pubburi.t_order WHERE o_id=?", java.sql.Timestamp.class, recent).toInstant();
            assertEquals(time, orders.selectById(recent).getOrderTime().toInstant());
            for (String denied : List.of("UPDATE pubburi.t_order SET price_basis=price_basis WHERE false", "UPDATE pubburi.t_order SET user_id=user_id WHERE false", "UPDATE pubburi.t_order_detail SET quantity=quantity WHERE false", "DELETE FROM pubburi.t_stamp WHERE false", "SELECT setval('pubburi.t_order_o_id_seq',1)", "TRUNCATE pubburi.t_order_detail")) {
                var error = assertThrows(DataAccessException.class, () -> runtime.execute(denied));
                assertEquals("42501", ((SQLException) error.getMostSpecificCause()).getSQLState());
            }
        } finally { for (int id : ids) sql.update("DELETE FROM pubburi.t_order WHERE o_id=? AND user_id=?", id, user); assertEquals(1, users.removeUser(user)); }
    }
    @Test void recentWindowUsesStatementTimeWhenCalledInsideAnOlderTransaction() {
        var sql = control(); String user = addUser(); var ids = new ArrayList<Integer>();
        try {
            new TransactionTemplate(transactions).execute(status -> {
                runtime.queryForObject("SELECT CURRENT_TIMESTAMP", java.sql.Timestamp.class); // start this transaction's clock
                sql.execute("SELECT pg_sleep(0.25)");
                int id = sql.queryForObject("INSERT INTO pubburi.t_order(user_id,order_type,order_time) VALUES(?,'TAKEOUT',statement_timestamp()-INTERVAL '1 month'-INTERVAL '0.1 seconds') RETURNING o_id", Integer.class, user);
                ids.add(id);
                assertFalse(orders.getLastMonthOrder(user).stream().anyMatch(o -> o.getoId() == id));
                assertTrue(orders.getLast6MonthOrder(user).stream().anyMatch(o -> o.getoId() == id));
                return null;
            });
        } finally { for (int id : ids) sql.update("DELETE FROM pubburi.t_order WHERE o_id=? AND user_id=?", id, user); assertEquals(1, users.removeUser(user)); }
    }
    @Test void insertedTimeUsesTheDatabaseStatementClockInsideAnOlderTransaction() {
        var sql = control(); String user = addUser(); var ids = new ArrayList<Integer>();
        try {
            new TransactionTemplate(transactions).execute(status -> {
                runtime.queryForObject("SELECT CURRENT_TIMESTAMP", java.sql.Timestamp.class);
                sql.execute("SELECT pg_sleep(0.25)");
                Instant lower = sql.queryForObject("SELECT statement_timestamp()", java.sql.Timestamp.class).toInstant();
                var value = new com.pubburi.pub.model.dto.Order(); value.setUserId(user); value.setOrderType("TAKEOUT");
                value.setCompleted("N"); value.setTotalPrice(0); value.setPriceBasis("ORDER_SNAPSHOT");
                assertEquals(1, orders.insert(value)); ids.add(value.getoId());
                Instant stored = orders.selectById(value.getoId()).getOrderTime().toInstant();
                assertFalse(stored.isBefore(lower));
                assertFalse(stored.isAfter(sql.queryForObject("SELECT statement_timestamp()", java.sql.Timestamp.class).toInstant()));
                return null;
            });
        } finally { for (int id : ids) sql.update("DELETE FROM pubburi.t_order WHERE o_id=? AND user_id=?", id, user); assertEquals(1, users.removeUser(user)); }
    }
    private Set<String> keys(com.fasterxml.jackson.databind.JsonNode value) { var names = new HashSet<String>(); value.fieldNames().forEachRemaining(names::add); return names; }
}
