package com.pubburi.rehearsal;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "PUBBURI_JPA_INTEGRATION", matches = "1")
class JpaAccountIntegrationTests extends JpaAccountTestSupport {
    @Test void registrationUsesRealInsertOnlyRepositoryAndNeverExposesCredentialsOrAcceptsRole() throws Exception {
        var sql = control(); String id = accountId(); var guest = client();
        try {
            assertTrue(request(guest, "GET", "/api/users/id-available?id=" + id, null).body().path("data").path("available").booleanValue());
            assertEquals(400, request(guest, "POST", "/api/users", Map.of("id", id, "name", " ", "pass", "abc")).status());
            var body = new HashMap<>(registration(id)); body.put("role", "ADMIN"); body.put("stamps", 999);
            assertEquals(201, request(guest, "POST", "/api/users", body).status());
            var stored = sql.queryForMap("SELECT password_hash,auth_epoch,role,stamps FROM pubburi.t_user WHERE id=?", id);
            assertTrue(((String) stored.get("password_hash")).startsWith("$2")); assertNotNull(stored.get("auth_epoch"));
            assertEquals("USER", stored.get("role")); assertEquals(0, stored.get("stamps"));
            assertEquals(400, request(guest, "POST", "/api/users", registration(id.toUpperCase(Locale.ROOT))).status());
            assertEquals(stored, sql.queryForMap("SELECT password_hash,auth_epoch,role,stamps FROM pubburi.t_user WHERE id=?", id));
            assertFalse(request(guest, "GET", "/api/users/id-available?id=" + id.toUpperCase(Locale.ROOT), null).body().path("data").path("available").booleanValue());
            var owner = login(id, password); var me = request(owner, "GET", "/api/auth/me", null);
            publicUser(me.body().path("data")); assertEquals(id, me.body().path("data").path("id").textValue());
            assertEquals(401, profile(guest, id).status());
            var response = profile(owner, id); assertEquals(200, response.status());
            assertEquals(Set.of("user", "orders", "grade"), keys(response.body().path("data"))); publicUser(response.body().path("data").path("user"));
            assertEquals(0, response.body().path("data").path("orders").size());
            assertEquals(200, request(owner, "POST", "/api/auth/logout", null).status()); assertEquals(401, profile(owner, id).status());
        } finally { clean(sql, id, null, List.of()); }
    }

    @Test void profileUsesLatestGradeAndTenOrderedSnapshotsWithExistingOwnerBoundaries() throws Exception {
        var sql = control(); var admin = loggedIn("ADMIN"); String id = join(); int product = create(admin, ownName()); var ids = new ArrayList<Integer>();
        try {
            var owner = login(id, password);
            for (int quantity = 1; quantity <= 12; quantity++) {
                int order = sql.queryForObject("INSERT INTO pubburi.t_order(user_id,order_type,total_price,price_basis) VALUES(?,'TAKEOUT',?,'ORDER_SNAPSHOT') RETURNING o_id", Integer.class, id, quantity * 1200); ids.add(order);
                sql.update("INSERT INTO pubburi.t_order_detail(order_id,product_id,quantity,product_name,product_type,product_img,unit_price,sum_price) VALUES(?,?,?,'Authored historical name','증류주','authored.png',1200,?)", order, product, quantity, quantity * 1200);
            }
            assertEquals(200, request(admin, "PUT", "/api/admin/users/" + id, Map.of("stamps", 51)).status());
            assertEquals(200, request(admin, "DELETE", "/api/admin/products/" + product, null).status());
            var response = profile(owner, id); assertEquals(200, response.status()); var data = response.body().path("data");
            assertEquals(51, data.path("user").path("stamps").intValue()); publicUser(data.path("user"));
            assertEquals(json.valueToTree(Map.of("img", "flower.png", "step", 1, "stepMax", 15, "to", 15, "title", "꽃")), data.path("grade"));
            assertEquals(10, data.path("orders").size());
            for (int i = 0; i < 10; i++) {
                var order = data.path("orders").get(i); assertEquals(ids.get(11 - i).intValue(), order.path("oId").intValue());
                assertEquals((12 - i) * 1200, order.path("totalPrice").intValue());
                assertEquals("Authored historical name", order.path("details").get(0).path("name").textValue());
                assertTrue(order.path("details").get(0).path("productId").isNull());
            }
            assertEquals(403, profile(loggedIn("USER"), id).status()); assertEquals(200, profile(admin, id).status());
            assertEquals(404, profile(admin, accountId()).status()); assertEquals(403, profile(owner, required("PUBBURI_JPA_ADMIN_ID")).status());
            var page = request(admin, "GET", "/api/admin/users?size=100", null); assertEquals(200, page.status());
            assertEquals(Set.of("items", "page", "size", "total", "totalPages", "hasNext"), keys(page.body().path("data")));
            page.body().path("data").path("items").forEach(this::publicUser);
            assertEquals(403, request(owner, "GET", "/api/admin/users", null).status());
        } finally { clean(sql, id, product, ids); }
    }

    @Test void adminPartialUpdatesPreserveOmittedCredentialsAndCurrentRoleRevokesOldPrivileges() throws Exception {
        var sql = control(); var admin = loggedIn("ADMIN"); String id = join();
        try {
            var owner = login(id, password); var before = sql.queryForMap("SELECT password_hash,auth_epoch FROM pubburi.t_user WHERE id=?", id);
            assertEquals(403, request(owner, "PUT", "/api/admin/users/" + id, Map.of("name", "forbidden")).status());
            assertEquals(200, request(admin, "PUT", "/api/admin/users/" + id, Map.of("name", "Authored renamed", "stamps", 77)).status());
            assertEquals(before, sql.queryForMap("SELECT password_hash,auth_epoch FROM pubburi.t_user WHERE id=?", id));
            var latest = request(owner, "GET", "/api/auth/me", null).body().path("data"); assertEquals("Authored renamed", latest.path("name").textValue()); assertEquals(77, latest.path("stamps").intValue());
            assertEquals(200, request(admin, "PUT", "/api/admin/users/" + id, Map.of("role", "ADMIN")).status());
            assertEquals(200, request(owner, "GET", "/api/admin/users", null).status());
            assertEquals(200, request(admin, "PUT", "/api/admin/users/" + id, Map.of("role", "USER")).status());
            assertEquals(403, request(owner, "GET", "/api/admin/users", null).status());
            assertEquals(200, request(owner, "GET", "/api/auth/me", null).status());
            var unchanged = accountState(sql, id);
            assertEquals(400, request(admin, "PUT", "/api/admin/users/" + id, Map.of("stamps", -1)).status()); assertEquals(unchanged, accountState(sql, id));
            assertEquals(404, request(admin, "PUT", "/api/admin/users/" + accountId(), Map.of("name", "missing")).status());
        } finally { clean(sql, id, null, List.of()); }
    }

    @Test void selfPasswordRotationKeepsOnlyTheUpdatingAdminSessionAndRejectsSelfDestruction() throws Exception {
        var sql = control(); var root = loggedIn("ADMIN"); String id = join(); String fresh = "Authored-fresh-password-84";
        try {
            assertEquals(200, request(root, "PUT", "/api/admin/users/" + id, Map.of("role", "ADMIN")).status());
            var self = login(id, password); var other = login(id, password);
            Object epoch = sql.queryForObject("SELECT auth_epoch FROM pubburi.t_user WHERE id=?", Object.class, id);
            assertEquals(409, request(self, "PUT", "/api/admin/users/" + id, Map.of("role", "USER")).status());
            assertEquals(409, request(self, "DELETE", "/api/admin/users/" + id, null).status());
            assertEquals(200, request(self, "PUT", "/api/admin/users/" + id, Map.of("pass", fresh, "name", "Authored retained admin")).status());
            assertNotEquals(epoch, sql.queryForObject("SELECT auth_epoch FROM pubburi.t_user WHERE id=?", Object.class, id));
            var current = request(self, "GET", "/api/auth/me", null); assertEquals(200, current.status()); publicUser(current.body().path("data"));
            assertEquals("Authored retained admin", current.body().path("data").path("name").textValue());
            assertEquals(401, request(other, "GET", "/api/auth/me", null).status());
            assertEquals(401, request(client(), "POST", "/api/auth/login", Map.of("id", id, "pass", password)).status());
            assertEquals(200, profile(login(id, fresh), id).status());
        } finally { clean(sql, id, null, List.of()); }
    }

    @Test void deletingAndRejoiningAnIdClearsPrivateChildrenButPreservesOldOrderSnapshots() throws Exception {
        var sql = control(); var admin = loggedIn("ADMIN"); String id = join(); int product = create(admin, ownName()); var ids = new ArrayList<Integer>();
        try {
            var owner = login(id, password); var dormant = login(id, password);
            var created = request(owner, "POST", "/api/orders", Map.of("orderTable", "authored note", "details", List.of(Map.of("productId", product, "quantity", 2))));
            assertEquals(201, created.status()); int order = created.body().path("data").path("oId").intValue(); ids.add(order); children(sql, id, product);
            assertEquals(403, request(owner, "DELETE", "/api/admin/users/" + id, null).status());
            assertEquals(200, request(admin, "DELETE", "/api/admin/users/" + id, null).status());
            assertEquals(404, request(admin, "DELETE", "/api/admin/users/" + id, null).status());
            var history = sql.queryForMap("SELECT user_id,order_table,total_price FROM pubburi.t_order WHERE o_id=?", order);
            assertNull(history.get("user_id")); assertNull(history.get("order_table")); assertEquals(2400, history.get("total_price"));
            assertEquals(1, sql.queryForObject("SELECT count(*) FROM pubburi.t_order_detail WHERE order_id=?", Integer.class, order));
            for (String table : List.of("t_stamp", "t_user_preference", "t_user_preference_value", "t_user_favorite")) assertEquals(0, sql.queryForObject("SELECT count(*) FROM pubburi." + table + " WHERE user_id=?", Integer.class, id));
            assertEquals(201, request(client(), "POST", "/api/users", registration(id)).status());
            assertEquals(401, profile(dormant, id).status());
            var replacement = login(id, password); assertEquals(0, profile(replacement, id).body().path("data").path("orders").size());
            assertEquals(403, request(replacement, "GET", "/api/orders/" + order, null).status());
            assertEquals(200, request(admin, "GET", "/api/orders/" + order, null).status());
        } finally { clean(sql, id, product, ids); }
    }

    @Test void concurrentPartialChangesAndOrdersDoNotRestoreOmittedStampOrCredentialState() throws Exception {
        var sql = control(); var admin = loggedIn("ADMIN"); String id = join(); int product = create(admin, ownName()); var executor = Executors.newFixedThreadPool(2);
        try {
            var owner = login(id, password); var secondAdmin = loggedIn("ADMIN");
            var before = sql.queryForMap("SELECT password_hash,auth_epoch,role FROM pubburi.t_user WHERE id=?", id);
            var start = new CountDownLatch(1);
            var name = executor.submit(() -> { start.await(); return request(admin, "PUT", "/api/admin/users/" + id, Map.of("name", "Authored simultaneous name")); });
            var stamps = executor.submit(() -> { start.await(); return request(secondAdmin, "PUT", "/api/admin/users/" + id, Map.of("stamps", 30)); });
            start.countDown(); assertEquals(200, name.get(12, TimeUnit.SECONDS).status()); assertEquals(200, stamps.get(12, TimeUnit.SECONDS).status());
            assertEquals("Authored simultaneous name", profile(owner, id).body().path("data").path("user").path("name").textValue());
            assertEquals(30, sql.queryForObject("SELECT stamps FROM pubburi.t_user WHERE id=?", Integer.class, id));
            var next = new CountDownLatch(1);
            var edit = executor.submit(() -> { next.await(); return request(admin, "PUT", "/api/admin/users/" + id, Map.of("name", "Authored ordered name")); });
            var order = executor.submit(() -> { next.await(); return request(owner, "POST", "/api/orders", Map.of("details", List.of(Map.of("productId", product, "quantity", 2)))); });
            next.countDown(); assertEquals(200, edit.get(12, TimeUnit.SECONDS).status()); assertEquals(201, order.get(12, TimeUnit.SECONDS).status());
            assertEquals(32, sql.queryForObject("SELECT stamps FROM pubburi.t_user WHERE id=?", Integer.class, id));
            assertEquals("Authored ordered name", profile(owner, id).body().path("data").path("user").path("name").textValue());
            assertEquals(before, sql.queryForMap("SELECT password_hash,auth_epoch,role FROM pubburi.t_user WHERE id=?", id));
        } finally { executor.shutdownNow(); assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS)); clean(sql, id, product, List.of()); }
    }
}
