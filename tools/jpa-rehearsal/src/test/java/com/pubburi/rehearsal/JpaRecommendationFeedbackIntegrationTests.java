package com.pubburi.rehearsal;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "server.port=0")
@Import(JpaFavoriteIntegrationTests.Probes.class)
@EnabledIfEnvironmentVariable(named = "PUBBURI_JPA_INTEGRATION", matches = "1")
class JpaRecommendationFeedbackIntegrationTests extends JpaAccountTestSupport {
    private static final String PATH = "/api/users/me/recommendations/feedback/";
    private static final String MATCHED = "{\"feedback\":\"MATCHED\"}";
    @Autowired JpaFavoriteIntegrationTests.Gate gate;
    private static final String NOT_INTERESTED = "{\"feedback\":\"NOT_INTERESTED\"}";

    private Reply feedback(HttpClient client, String owner, String method, String product, String body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + PATH + product))
            .timeout(Duration.ofSeconds(8)).header("Accept", "application/json");
        if (owner != null) builder.header(RecommendationFeedbackController.OWNER_HEADER,
            URLEncoder.encode(owner, StandardCharsets.UTF_8).replace("+", "%20"));
        if (body != null) builder.header("Content-Type", "application/json");
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        var response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        assertEquals("private, no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        return new Reply(response.statusCode(), json.readTree(response.body()));
    }

    private void assertChoice(Reply reply, int product, String choice) {
        assertEquals(200, reply.status(), () -> reply.body().toString());
        var data = reply.body().path("data");
        assertEquals(Set.of("productId", "feedback"), keys(data));
        assertEquals(product, data.path("productId").intValue());
        assertEquals(choice, data.path("feedback").textValue());
    }

    @Test void feedbackHasOwnAccountLifetimeAndDoesNotChangePreferencesOrFavorites() throws Exception {
        var sql = control();
        var admin = loggedIn("ADMIN");
        var user = join();
        var other = join();
        int product = create(admin, ownName());
        var key = String.valueOf(product);
        try {
            var owner = login(user, password);
            var stranger = login(other, password);
            var before = accountState(sql, user);
            assertEquals(401, feedback(client(), user, "GET", key, null).status());
            assertEquals(400, feedback(owner, null, "GET", key, null).status());
            assertEquals(401, feedback(stranger, user, "PUT", key, MATCHED).status());
            assertEquals(400, feedback(owner, user, "GET", key + "?userId=" + other, null).status());
            assertEquals(400, feedback(owner, user, "GET", "01", null).status());
            for (String invalid : List.of("{\"feedback\":1}", "{\"feedback\":\"matched\"}",
                    "{\"feedback\":\"MATCHED\",\"userId\":\"override\"}", "{\"feedback\":null}",
                    "{\"feedback\":\"MATCHED\",\"feedback\":\"NOT_INTERESTED\"}", MATCHED + " {}")) {
                assertEquals(400, feedback(owner, user, "PUT", key, invalid).status());
            }
            assertChoice(feedback(owner, user, "GET", key, null), product, null);
            assertChoice(feedback(owner, user, "PUT", key, MATCHED), product, "MATCHED");
            String row = "SELECT xmin::text FROM pubburi.t_user_recommendation_feedback WHERE user_id=? AND product_id=?";
            var version = sql.queryForObject(row, String.class, user, product);
            assertChoice(feedback(owner, user, "PUT", key, MATCHED), product, "MATCHED");
            assertEquals(version, sql.queryForObject(row, String.class, user, product));
            assertChoice(feedback(stranger, other, "GET", key, null), product, null);
            assertChoice(feedback(admin, required("PUBBURI_JPA_ADMIN_ID"), "GET", key, null), product, null);
            assertChoice(feedback(owner, user, "PUT", key, NOT_INTERESTED), product, "NOT_INTERESTED");
            assertChoice(feedback(login(user, password), user, "GET", key, null), product, "NOT_INTERESTED");
            assertEquals(before, accountState(sql, user));
            assertChoice(feedback(owner, user, "DELETE", key, null), product, null);
            assertChoice(feedback(owner, user, "DELETE", key, null), product, null);
            assertChoice(feedback(owner, user, "PUT", key, MATCHED), product, "MATCHED");
            for (String column : List.of("user_id", "product_id")) {
                assertThrows(DataAccessException.class, () -> runtime.execute(
                    "UPDATE pubburi.t_user_recommendation_feedback SET " + column + "=" + column + " WHERE false"));
            }
            var executor = Executors.newSingleThreadExecutor();
            try (var pause = gate.arm("userLock")) {
                var stale = executor.submit(() -> feedback(owner, user, "DELETE", key, null));
                assertTrue(pause.entered.await(4, TimeUnit.SECONDS));
                assertEquals(200, request(admin, "PUT", "/api/admin/users/" + user,
                    Map.of("pass", "Authored-rotated-feedback")).status());
                pause.release.countDown();
                assertEquals(401, stale.get(6, TimeUnit.SECONDS).status());
            } finally {
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS));
            }
            assertEquals(401, feedback(owner, user, "DELETE", key, null).status());
            var current = login(user, "Authored-rotated-feedback");
            assertChoice(feedback(current, user, "GET", key, null), product, "MATCHED");
            assertEquals(200, request(admin, "DELETE", "/api/admin/users/" + user, null).status());
            assertEquals(201, request(client(), "POST", "/api/users", registration(user)).status());
            var rejoined = login(user, password);
            assertChoice(feedback(rejoined, user, "GET", key, null), product, null);
            assertChoice(feedback(rejoined, user, "PUT", key, MATCHED), product, "MATCHED");
            assertEquals(200, request(admin, "DELETE", "/api/admin/products/" + product, null).status());
            assertChoice(feedback(rejoined, user, "GET", key, null), product, null);
            assertEquals(404, feedback(rejoined, user, "PUT", key, MATCHED).status());
        } finally {
            clean(sql, user, product, List.of());
            clean(sql, other, null, List.of());
        }
    }
}
