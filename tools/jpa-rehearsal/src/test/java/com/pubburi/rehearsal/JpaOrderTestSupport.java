package com.pubburi.rehearsal;

import static org.junit.jupiter.api.Assertions.*;

import com.pubburi.pub.model.dto.User;
import com.pubburi.pub.model.service.UserService;
import java.net.http.HttpClient;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

abstract class JpaOrderTestSupport extends JpaWriteTestSupport {
    @Autowired UserService users;
    final String password = "Authored-order-password-42";
    String addUser() {
        String id = "ci_jpa_order_" + UUID.randomUUID().toString().replace("-", "");
        assertEquals(1, users.addUser(new User(id, "Authored order user", password, 0, "USER")));
        return id;
    }
    HttpClient loginUser(String id) throws Exception {
        var client = client();
        assertEquals(200, request(client, "POST", "/api/auth/login", Map.of("id", id, "pass", password)).status());
        return client;
    }
    Map<String, Object> order(int product, int quantity) {
        return Map.of("orderType", "TAKEOUT", "orderTable", "authored note",
            "details", List.of(Map.of("productId", product, "quantity", quantity)));
    }
    Map<String, Object> orderState(JdbcTemplate sql, String user, int product) {
        var result = new HashMap<String, Object>(); result.put("product", state(sql, product));
        result.put("user", sql.queryForList("SELECT * FROM pubburi.t_user WHERE id=?", user));
        result.put("orders", sql.queryForList("SELECT * FROM pubburi.t_order WHERE user_id=? ORDER BY o_id", user));
        result.put("details", sql.queryForList("SELECT d.* FROM pubburi.t_order_detail d JOIN pubburi.t_order o ON o.o_id=d.order_id WHERE o.user_id=? ORDER BY d.d_id", user));
        result.put("stamps", sql.queryForList("SELECT * FROM pubburi.t_stamp WHERE user_id=? ORDER BY id", user));
        return result;
    }
    void clean(JdbcTemplate sql, String user, int product) {
        sql.update("DELETE FROM pubburi.t_order WHERE user_id=?", user);
        assertEquals(1, users.removeUser(user));
        sql.update("DELETE FROM pubburi.t_product WHERE id=? AND name LIKE 'ci_jpa_write_%'", product);
        // Immutable producer records are intentionally retained until the owned DB is disposed.
    }
}
