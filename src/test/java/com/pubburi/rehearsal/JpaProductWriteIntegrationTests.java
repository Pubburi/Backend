package com.pubburi.rehearsal;

import static org.junit.jupiter.api.Assertions.*;

import com.pubburi.pub.model.dao.ProductDao;
import com.pubburi.pub.model.dao.ProductSearchOutboxDao;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "PUBBURI_JPA_INTEGRATION", matches = "1")
class JpaProductWriteIntegrationTests extends JpaWriteTestSupport {
    @Autowired ProductDao products;
    @Autowired ProductSearchOutboxDao outbox;
    @Autowired PlatformTransactionManager transactions;
    @Autowired DataSource dataSource;

    @Test void invalidNumericProductFieldsCannotWriteProductsOrSearchEvents() throws Exception {
        var sql = control();var admin = loggedIn("ADMIN");String name = ownName();
        var identity = sourceIdentity(sql);
        var created = request(admin,"POST","/api/admin/products",product(name,1));
        assertEquals(201,created.status());int id = created.body().path("data").path("id").intValue();
        String counts = "SELECT (SELECT count(*) FROM pubburi.t_product) AS products,"
            + "(SELECT count(*) FROM pubburi.t_product_search_version) AS versions,"
            + "(SELECT count(*) FROM pubburi.t_product_search_outbox) AS events,"
            + "(SELECT count(*) FROM pubburi.t_product_search_delivery) AS deliveries";
        String sequences = "SELECT 'product' AS sequence_name,last_value,is_called FROM pubburi.t_product_id_seq UNION ALL "
            + "SELECT 'event' AS sequence_name,last_value,is_called FROM pubburi.t_product_search_outbox_event_id_seq ORDER BY sequence_name";
        try {
            var stable = state(sql,id);var stableCounts = sql.queryForMap(counts);var stableSequences = sql.queryForList(sequences);
            var invalidInputs = new ArrayList<ObjectNode>();
            for (String field : List.of("price","orderCount")) {
                for (String token : List.of("1200.9","1200.0","1e3","\"1200\"","2147483648","-2147483649")) {
                    ObjectNode input = json.valueToTree(product(name,1200));
                    input.set(field,json.readTree(token));invalidInputs.add(input);
                }
            }
            for (String mode : List.of("null","omitted","zero","negative")) {
                ObjectNode input = json.valueToTree(product(name,1200));
                switch (mode) {
                    case "null" -> input.putNull("price");
                    case "omitted" -> input.remove("price");
                    case "zero" -> input.put("price",0);
                    case "negative" -> input.put("price",-1);
                }
                invalidInputs.add(input);
            }
            ObjectNode negativeCount = json.valueToTree(product(name,1200));
            negativeCount.put("orderCount",-1);invalidInputs.add(negativeCount);
            ObjectNode omittedAbv = json.valueToTree(product(name, 1200));
            omittedAbv.remove("abv");
            invalidInputs.add(omittedAbv);
            for (String token : List.of("null", "100.000001", "\"7.25\"")) {
                ObjectNode input = json.valueToTree(product(name, 1200));
                input.set("abv", json.readTree(token));
                invalidInputs.add(input);
            }
            for (var input : invalidInputs) {
                assertEquals(400,request(admin,"PUT","/api/admin/products/"+id,input.toString()).status());
                assertEquals(400,request(admin,"POST","/api/admin/products",input.toString()).status());
                assertEquals(stable,state(sql,id));assertEquals(stableCounts,sql.queryForMap(counts));
                assertEquals(stableSequences,sql.queryForList(sequences));
            }
            ObjectNode minimum = json.valueToTree(product(name,1));
            ObjectNode maximum = json.valueToTree(product(name,Integer.MAX_VALUE));maximum.put("orderCount",Integer.MAX_VALUE);
            ObjectNode nullCount = json.valueToTree(product(name,1200));nullCount.putNull("orderCount");
            ObjectNode omittedCount = json.valueToTree(product(name,1200));omittedCount.remove("orderCount");
            ObjectNode zeroAbv = json.valueToTree(product(name, 1200));
            zeroAbv.put("abv", 0);
            for (var input : List.of(minimum,maximum,nullCount,omittedCount,zeroAbv)) {
                int previousEvents = events(sql,id).size();
                assertEquals(200,request(admin,"PUT","/api/admin/products/"+id,input.toString()).status());
                var current = request(client(),"GET","/api/products/"+id,null).body().path("data");
                assertEquals(input.path("price").intValue(),current.path("price").intValue());
                assertEquals(input.path("orderCount").intValue(),current.path("orderCount").intValue());
                assertTrue(current.path("abv").isNumber());
                assertEquals(input.path("abv").floatValue(), current.path("abv").floatValue());
                var emitted = events(sql,id);assertEquals(previousEvents+1,emitted.size());
                var latest = emitted.get(emitted.size()-1);
                assertEquals("PENDING",latest.get("status"));
                var document = json.readTree((String)latest.get("payload")).path("document");
                assertEquals(current.path("price"),document.path("priceKrw"));
                assertEquals(current.path("orderCount"),document.path("orderCount"));
                assertEquals(current.path("abv"), document.path("abvPercent"));
            }
            assertEquals(identity,sourceIdentity(sql));
        } finally {
            assertEquals(200,request(admin,"DELETE","/api/admin/products/"+id,null).status());
        }
    }

    @Test void adminWritesKeepDtoValidationAndProduceOnePendingEventPerSuccessfulTransaction() throws Exception {
        var sql = control();
        var identity = sourceIdentity(sql);
        var anonymous = client();
        var member = loggedIn("USER");
        var admin = loggedIn("ADMIN");
        String name = ownName();
        assertEquals(401, request(anonymous, "POST", "/api/admin/products", product(name, 1200)).status());
        assertEquals(403, request(member, "POST", "/api/admin/products", product(name, 1200)).status());
        int id = create(admin, name);
        for (var actor : List.of(anonymous, member)) {
            int expected = actor == anonymous ? 401 : 403;
            assertEquals(expected, request(actor, "PUT", "/api/admin/products/" + id, product(name, 1300)).status());
            assertEquals(expected, request(actor, "DELETE", "/api/admin/products/" + id, null).status());
            assertEquals(expected, request(actor, "PUT", "/api/admin/products/" + id + "/features", Map.of()).status());
        }
        try {
            var created = request(anonymous, "GET", "/api/products/" + id, null).body().path("data");
            assertEquals(1200, created.path("price").intValue());
            assertEquals(7, created.size());
            var first = events(sql, id);
            assertEquals(1, first.size());
            var projection = json.readTree((String) first.get(0).get("payload")).path("document");
            assertTrue(projection.path("tasteTags").isNull());
            assertTrue(projection.path("aromaTags").isNull());
            assertEquals(7, projection.size());
            assertFalse(projection.has("sourceUrl"));

            assertEquals(200, request(admin, "PUT", "/api/admin/products/" + id, product(name + " edited", 2300)).status());
            var features = Map.of("volumeMl", 750, "tasteTags", List.of(" DRY ", "fruity"),
                "aromaTags", List.of(), "sourceName", "Authored verification only");
            var replaced = request(admin, "PUT", "/api/admin/products/" + id + "/features", features);
            assertEquals(200, replaced.status());
            assertEquals(json.readTree("[\"dry\",\"fruity\"]"), replaced.body().path("data").path("tasteTags"));
            assertTrue(replaced.body().path("data").path("aromaTags").isEmpty());
            assertEquals(200, request(admin, "PUT", "/api/admin/products/" + id + "/features", features).status());
            assertEquals(2, sql.queryForObject("SELECT count(*) FROM pubburi.t_product_feature_tag WHERE product_id=?", Integer.class, id));
            assertEquals(200, request(admin, "PUT", "/api/admin/products/" + id + "/features", Map.of()).status());
            assertTrue(request(anonymous, "GET", "/api/products/" + id + "/features", null).body().path("data").path("tasteTags").isNull());
            assertEquals(0, sql.queryForObject("SELECT count(*) FROM pubburi.t_product_feature_tag WHERE product_id=?", Integer.class, id));

            var stable = state(sql, id);
            for (String invalid : List.of("{\"volumeMl\":750.5,\"sourceName\":\"authored\"}",
                    "{\"volumeMl\":\"750\",\"sourceName\":\"authored\"}", "{\"tasteTags\":[3],\"sourceName\":\"authored\"}",
                    "{\"tasteTags\":[]}", "{\"tasteTags\":[\"DRY\",\"dry\"],\"sourceName\":\"authored\"}", "{\"description\":true,\"sourceName\":\"authored\"}",
                    "{\"sourceUrl\":\"https://user:pass@example.invalid/\"}")) {
                assertEquals(400, request(admin, "PUT", "/api/admin/products/" + id + "/features", invalid).status());
            }
            assertEquals(400, request(admin, "PUT", "/api/admin/products/" + id, product(name, 0)).status());
            assertEquals(stable, state(sql, id));
            assertEquals(404, request(admin, "PUT", "/api/admin/products/0", product(name, 1)).status());
            assertEquals(404, request(admin, "DELETE", "/api/admin/products/0", null).status());
            assertEquals(404, request(admin, "PUT", "/api/admin/products/0/features", Map.of()).status());
            var emitted = events(sql, id);
            assertEquals(List.of(1L, 2L, 3L, 4L, 5L), emitted.stream().map(e -> e.get("source_version")).toList());
            for (var event : emitted) {
                assertEquals("PENDING", event.get("status"));
                assertNull(event.get("claim_token"));
                assertNull(event.get("lease_until"));
                assertNull(event.get("delivered_at"));
                assertEquals(0, event.get("attempts"));
            }
            assertEquals(identity, sourceIdentity(sql));
        } finally {
            assertEquals(200, request(admin, "DELETE", "/api/admin/products/" + id, null).status());
        }
        var deleted = events(sql, id);
        assertEquals(6, deleted.size());
        assertEquals(true, deleted.get(5).get("deleted"));
        assertTrue(json.readTree((String) deleted.get(5).get("payload")).path("document").isNull());
    }

    @Test void deleteKeepsOrderSnapshotsAndRecreatedExplicitIdContinuesItsVersion() throws Exception {
        var sql = control();
        var admin = loggedIn("ADMIN");
        String name = ownName();
        int id = create(admin, name);
        String userId = "ci_jpa_hist_" + UUID.randomUUID().toString().replace("-", "");
        sql.update("INSERT INTO pubburi.t_user(id,name,password_hash,role,auth_epoch,stamps) VALUES(?,?,'authored-no-login','USER',CAST(? AS uuid),0)", userId, userId, UUID.randomUUID().toString());
        int order = sql.queryForObject("INSERT INTO pubburi.t_order(user_id,order_type,order_table,total_price,price_basis) VALUES(?,'table','ci_jpa_history',2400,'ORDER_SNAPSHOT') RETURNING o_id", Integer.class, userId);
        int detail = sql.queryForObject("INSERT INTO pubburi.t_order_detail(order_id,product_id,quantity,product_name,product_type,product_img,unit_price,sum_price) VALUES(?,?,2,?,'증류주','authored.png',1200,2400) RETURNING d_id", Integer.class, order, id, name);
        try {
            assertEquals(200, request(admin, "PUT", "/api/admin/products/" + id + "/features", Map.of("tasteTags", List.of("dry"), "sourceName", "Authored source")).status());
            assertEquals(200, request(admin, "PUT", "/api/admin/products/" + id, product(name + " renamed", 9900)).status());
            assertEquals(200, request(admin, "DELETE", "/api/admin/products/" + id, null).status());
            var history = sql.queryForMap("SELECT product_id,product_name,unit_price,sum_price,quantity FROM pubburi.t_order_detail WHERE d_id=?", detail);
            assertNull(history.get("product_id"));
            assertEquals(name, history.get("product_name"));
            assertEquals(1200, history.get("unit_price"));
            assertEquals(2400, history.get("sum_price"));
            assertEquals(2400, sql.queryForObject("SELECT total_price FROM pubburi.t_order WHERE o_id=?", Integer.class, order));
            assertEquals(0, sql.queryForObject("SELECT count(*) FROM pubburi.t_product_feature WHERE product_id=?", Integer.class, id));
            assertEquals(0, sql.queryForObject("SELECT count(*) FROM pubburi.t_product_feature_tag WHERE product_id=?", Integer.class, id));
            assertEquals(404, request(client(), "GET", "/api/products/" + id, null).status());
            // Only this newly authored ID is recreated. No sequence or immutable event is reset.
            sql.update("INSERT INTO pubburi.t_product(id,name,type,price,img,abv,order_count) VALUES(?,?,'증류주',1700,'authored.png',10,0)", id, name + " recreated");
            assertEquals(200, request(admin, "PUT", "/api/admin/products/" + id, product(name + " continued", 1800)).status());
            assertEquals(List.of(1L, 2L, 3L, 4L, 5L), events(sql, id).stream().map(e -> e.get("source_version")).toList());
            assertNull(sql.queryForObject("SELECT product_id FROM pubburi.t_order_detail WHERE d_id=?", Integer.class, detail));
        } finally {
            if (products.selectById(id) != null) assertEquals(200, request(admin, "DELETE", "/api/admin/products/" + id, null).status());
            sql.update("DELETE FROM pubburi.t_order WHERE o_id=? AND user_id=?", order, userId);
            sql.update("DELETE FROM pubburi.t_user WHERE id=?", userId);
        }
    }

    @Test void boundJsonRetainsWhitespaceAndKeyOrderWithoutReencodingExistingEvents() throws Exception {
        var sql = control();
        var preserved = sql.queryForList("SELECT event_id,payload::text FROM pubburi.t_product_search_outbox ORDER BY event_id");
        var admin = loggedIn("ADMIN");
        int id = create(admin, ownName());
        String raw = "{ \"sourceVersion\" : 2, \"productId\" : " + id + ", \"deleted\" : false, \"document\" : { \"tasteTags\" : null, \"name\" : \"authored\" } }";
        try {
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                assertNotNull(products.selectByIdForUpdate(id));
                outbox.ensureVersion(id);
                assertEquals(1, outbox.advanceVersion(id));
                assertEquals(2L, outbox.selectVersionForUpdate(id));
                assertEquals(1, outbox.append(id, 2, false, raw));
                assertEquals(1, outbox.schedule(id, 2));
            });
            assertEquals(raw, sql.queryForObject("SELECT payload::text FROM pubburi.t_product_search_outbox WHERE product_id=? AND source_version=2", String.class, id));
            for (var event : preserved) assertEquals(event.get("payload"), sql.queryForObject("SELECT payload::text FROM pubburi.t_product_search_outbox WHERE event_id=?", String.class, event.get("event_id")));
        } finally { assertEquals(200, request(admin, "DELETE", "/api/admin/products/" + id, null).status()); }
    }

    @Test void simultaneousWritersSerializeEventsAndFullProductLockConflictsWithFkKeyShare() throws Exception {
        var admin = loggedIn("ADMIN");
        var sql = control();
        String name = ownName();
        int id = create(admin, name);
        var workers = Executors.newFixedThreadPool(2);
        try {
            var start = new CountDownLatch(1);
            var first = workers.submit(() -> { start.await(); return request(admin, "PUT", "/api/admin/products/" + id, product(name + " A", 2100)); });
            var second = workers.submit(() -> { start.await(); return request(admin, "PUT", "/api/admin/products/" + id + "/features", Map.of("tasteTags", List.of("dry"), "sourceName", "Authored concurrent")); });
            start.countDown();
            assertEquals(200, first.get(8, TimeUnit.SECONDS).status());
            assertEquals(200, second.get(8, TimeUnit.SECONDS).status());
            var emitted = events(sql, id);
            assertEquals(List.of(1L, 2L, 3L), emitted.stream().map(e -> e.get("source_version")).toList());
            var latest = json.readTree((String) emitted.get(2).get("payload")).path("document");
            assertEquals(2100, latest.path("priceKrw").intValue());
            assertEquals(json.readTree("[\"dry\"]"), latest.path("tasteTags"));
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                assertNotNull(products.selectByIdForUpdate(id));
                var blocked = workers.submit(() -> {
                    try (Connection connection = dataSource.getConnection(); var statement = connection.createStatement()) {
                        connection.setAutoCommit(false);
                        statement.execute("SET LOCAL lock_timeout='250ms'");
                        try (var rows = statement.executeQuery("SELECT id FROM pubburi.t_product WHERE id=" + id + " FOR KEY SHARE")) {
                            rows.next(); return "unexpected_lock_acquired";
                        } catch (SQLException error) { connection.rollback(); return error.getSQLState(); }
                    }
                });
                try { assertEquals("55P03", blocked.get(3, TimeUnit.SECONDS)); }
                catch (Exception error) { throw new AssertionError(error); }
            });
        } finally {
            workers.shutdownNow(); assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(200, request(admin, "DELETE", "/api/admin/products/" + id, null).status());
        }
    }

    @Test void exhaustedVersionRollsBackTheBusinessUpdateWithoutResettingCounter() throws Exception {
        var sql = control(); var admin = loggedIn("ADMIN"); String name = ownName();
        int id = sql.queryForObject("INSERT INTO pubburi.t_product(name,type,price,img,abv,order_count) VALUES(?,'증류주',1200,'authored.png',10,0) RETURNING id", Integer.class, name);
        sql.update("INSERT INTO pubburi.t_product_search_version(product_id,source_version) VALUES(?,9223372036854775807)", id);
        var before = state(sql, id);
        try {
            assertEquals(500, request(admin, "PUT", "/api/admin/products/" + id, product(name + " must rollback", 2300)).status());
            assertEquals(before, state(sql, id));
        } finally { sql.update("DELETE FROM pubburi.t_product WHERE id=? AND name=?", id, name); }
    }
}
