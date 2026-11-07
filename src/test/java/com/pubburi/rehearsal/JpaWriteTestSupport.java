package com.pubburi.rehearsal;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

abstract class JpaWriteTestSupport {
    @LocalServerPort int port;
    @Autowired Environment environment;
    @Autowired JdbcTemplate runtime;
    final ObjectMapper json = new ObjectMapper();
    record Reply(int status, JsonNode body) { }

    HttpClient client() {
        return HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
            .connectTimeout(Duration.ofSeconds(3)).build();
    }

    Reply request(HttpClient client, String method, String path, Object body) throws Exception {
        return request(client, method, path, body, Map.of());
    }

    Reply request(HttpClient client, String method, String path, Object body, Map<String, String> headers) throws Exception {
        String text = body instanceof String ? (String) body : body == null ? null : json.writeValueAsString(body);
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .timeout(Duration.ofSeconds(8)).header("Accept", "application/json");
        builder.method(method, text == null ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(text, StandardCharsets.UTF_8));
        if (text != null) builder.header("Content-Type", "application/json");
        headers.forEach(builder::header);
        var response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return new Reply(response.statusCode(), json.readTree(response.body()));
    }

    HttpClient loggedIn(String role) throws Exception {
        var client = client();
        var reply = request(client, "POST", "/api/auth/login", Map.of(
            "id", required("PUBBURI_JPA_" + role + "_ID"),
            "pass", required("PUBBURI_JPA_" + role + "_PASSWORD")));
        assertEquals(200, reply.status());
        return client;
    }

    String required(String key) {
        String value = environment.getProperty(key);
        assertNotNull(value, key + " required");
        return value;
    }

    JdbcTemplate control() {
        var values = new HashMap<String, String>();
        for (String key : List.of("PUBBURI_JPA_REHEARSAL", "PUBBURI_PG_URL", "PUBBURI_PG_DB_NAME",
                "PUBBURI_PG_USER", "PUBBURI_PG_PASSWORD", "PUBBURI_PG_RUN_TOKEN", "PUBBURI_PG_CA",
                "PUBBURI_JPA_SOURCE_ID")) values.put(key, required(key));
        var settings = RehearsalSettings.from(values);
        var source = new DriverManagerDataSource();
        source.setUrl(settings.url());
        source.setConnectionProperties(settings.properties());
        source.setUsername(required("PUBBURI_JPA_CONTROL_USER"));
        source.setPassword(required("PUBBURI_JPA_CONTROL_PASSWORD"));
        var sql = new JdbcTemplate(source);
        assertEquals(1, sql.queryForObject("SELECT count(*) FROM public.pubburi_pg_migration_guard WHERE token=?",
            Integer.class, settings.token()));
        return sql;
    }

    String ownName() { return "ci_jpa_write_" + UUID.randomUUID().toString().replace("-", ""); }
    Map<String, Object> product(String name, int price) {
        return Map.of("name", name, "type", "증류주", "price", price, "img", "authored.png", "abv", 15.5,
            "orderCount", 0);
    }
    int create(HttpClient admin, String name) throws Exception {
        Reply result = request(admin, "POST", "/api/admin/products", product(name, 1200));
        assertEquals(201, result.status(), result.body().toString());
        return result.body().path("data").path("id").intValue();
    }
    List<Map<String, Object>> events(JdbcTemplate sql, int id) {
        return sql.queryForList("""
            SELECT e.source_version, e.deleted, e.payload::text AS payload, d.status, d.claim_token,
                d.lease_until, d.delivered_at, d.attempts
            FROM pubburi.t_product_search_outbox e
            JOIN pubburi.t_product_search_delivery d USING(event_id)
            WHERE e.product_id=? ORDER BY e.source_version
            """, id);
    }
    Map<String, Object> state(JdbcTemplate sql, int id) {
        var result = new HashMap<String, Object>();
        result.put("product", sql.queryForList("SELECT * FROM pubburi.t_product WHERE id=?", id));
        result.put("features", sql.queryForList("SELECT * FROM pubburi.t_product_feature WHERE product_id=?", id));
        result.put("tags", sql.queryForList("SELECT * FROM pubburi.t_product_feature_tag WHERE product_id=? ORDER BY kind,tag", id));
        result.put("version", sql.queryForList("SELECT * FROM pubburi.t_product_search_version WHERE product_id=?", id));
        result.put("events", events(sql, id));
        result.put("history", sql.queryForList("SELECT * FROM pubburi.t_order_detail WHERE product_id=? ORDER BY d_id", id));
        return result;
    }
    Map<String, Object> sourceIdentity(JdbcTemplate sql) {
        return sql.queryForMap("SELECT source_id::text,fingerprint,identity_json::text,bound_at FROM pubburi.t_product_search_relay_target WHERE singleton_id=1");
    }
}
