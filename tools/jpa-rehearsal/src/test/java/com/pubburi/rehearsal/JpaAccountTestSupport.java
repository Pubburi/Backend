package com.pubburi.rehearsal;

import static org.junit.jupiter.api.Assertions.*;

import java.net.http.HttpClient;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

abstract class JpaAccountTestSupport extends JpaWriteTestSupport {
    final String password = "Authored-account-password-42";
    String accountId() { return "ci_jpa_account_" + UUID.randomUUID().toString().replace("-", ""); }
    Map<String, Object> registration(String id) { return Map.of("id", id, "name", "Authored account", "pass", password); }
    String join() throws Exception {
        String id = accountId(); assertEquals(201, request(client(), "POST", "/api/users", registration(id)).status()); return id;
    }
    HttpClient login(String id, String pass) throws Exception {
        var client = client(); assertEquals(200, request(client, "POST", "/api/auth/login", Map.of("id", id, "pass", pass)).status()); return client;
    }
    Reply profile(HttpClient client, String id) throws Exception { return request(client, "GET", "/api/users/" + id + "/profile", null); }
    void publicUser(com.fasterxml.jackson.databind.JsonNode value) {
        assertEquals(Set.of("id", "name", "stamps", "role", "admin"), keys(value));
    }
    Set<String> keys(com.fasterxml.jackson.databind.JsonNode node) { var result = new HashSet<String>(); node.fieldNames().forEachRemaining(result::add); return result; }
    Map<String, Object> accountState(JdbcTemplate sql, String id) {
        var state = new HashMap<String, Object>();
        state.put("user", sql.queryForList("SELECT * FROM pubburi.t_user WHERE id=?", id));
        state.put("orders", sql.queryForList("SELECT * FROM pubburi.t_order WHERE user_id=? ORDER BY o_id", id));
        state.put("details", sql.queryForList("SELECT d.* FROM pubburi.t_order_detail d JOIN pubburi.t_order o ON o.o_id=d.order_id WHERE o.user_id=? ORDER BY d.d_id", id));
        state.put("stamps", sql.queryForList("SELECT * FROM pubburi.t_stamp WHERE user_id=? ORDER BY id", id));
        state.put("preference", sql.queryForList("SELECT * FROM pubburi.t_user_preference WHERE user_id=?", id));
        state.put("values", sql.queryForList("SELECT * FROM pubburi.t_user_preference_value WHERE user_id=? ORDER BY kind,value_text", id));
        state.put("favorites", sql.queryForList("SELECT * FROM pubburi.t_user_favorite WHERE user_id=? ORDER BY product_id", id));
        return state;
    }
    void children(JdbcTemplate sql, String id, int product) {
        sql.update("INSERT INTO pubburi.t_user_preference(user_id,survey_status,taste_known) VALUES(?,'COMPLETED',true)", id);
        sql.update("INSERT INTO pubburi.t_user_preference_value(user_id,kind,value_text) VALUES(?,'TASTE','authored')", id);
        sql.update("INSERT INTO pubburi.t_user_favorite(user_id,product_id) VALUES(?,?)", id, product);
    }
    void clean(JdbcTemplate sql, String id, Integer product, List<Integer> orders) {
        assertTrue(id.matches("ci_jpa_account_[a-f0-9]{32}"));
        for (int order : orders) sql.update("DELETE FROM pubburi.t_order WHERE o_id=?", order);
        sql.update("DELETE FROM pubburi.t_order WHERE user_id=?", id);
        sql.update("DELETE FROM pubburi.t_user WHERE id=?", id);
        if (product != null) sql.update("DELETE FROM pubburi.t_product WHERE id=? AND name LIKE 'ci_jpa_write_%'", product);
    }
}
