package com.pubburi.migration;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Writes only the authored order slice into a separately fenced legacy MySQL fixture. */
final class ReverseOrderRehearsal {
    private static final SourceContract.Table ORDERS = table("t_order");
    private static final SourceContract.Table DETAILS = table("t_order_detail");

    private ReverseOrderRehearsal() { }

    static void verify(Map<String, String> env, Connection pg, SourceArtifact.Snapshot source) throws Exception {
        var reverse = new HashMap<>(env);
        for (String key : List.of("URL", "DB_NAME", "USER", "PASSWORD", "RUN_TOKEN", "WINDOW_UUID")) {
            reverse.put("PUBBURI_SOURCE_" + key, env.get("PUBBURI_SOURCE_REVERSE_" + key));
        }
        var legacy = SourceSettings.from(reverse);
        assertEquals("source_reverse_order_ci", legacy.database());
        assertEquals("source_reverse_writer", legacy.user());
        var original = SourceSettings.from(env);
        assertNotEquals(original.database(), legacy.database());

        try (var mysql = legacy.connect()) {
            SourceSession.guard(mysql, legacy);
            var beforeOrders = legacyRows(mysql, ORDERS);
            var beforeDetails = legacyRows(mysql, DETAILS);
            assertFalse(beforeOrders.isEmpty());
            assertFalse(beforeDetails.isEmpty());

            int fractional = authoredOrder(pg, "2026-11-15T04:00:00.123456Z");
            assertEquals("source_typed_value_invalid", assertThrows(IllegalArgumentException.class,
                    () -> pgRows(pg, ORDERS, "o_id", fractional)).getMessage());
            assertEquals(beforeOrders, legacyRows(mysql, ORDERS));
            assertEquals(beforeDetails, legacyRows(mysql, DETAILS));

            int orderId = authoredOrder(pg, "2026-11-15T04:00:01Z");
            try (var insert = pg.prepareStatement("""
                    INSERT INTO pubburi.t_order_detail
                    (order_id,product_id,quantity,product_name,product_type,product_img,unit_price,sum_price)
                    VALUES (?,NULL,2,'삭제 상품 🌾','탁주','authored-a.webp',7500,15000),
                           (?,NULL,1,'두 번째 품목','약주','authored-b.webp',3000,3000)
                    """)) {
                insert.setInt(1, orderId);
                insert.setInt(2, orderId);
                assertEquals(2, insert.executeUpdate());
            }
            var order = pgRows(pg, ORDERS, "o_id", orderId).get(0);
            var details = pgRows(pg, DETAILS, "order_id", orderId);
            assertEquals(2, details.size());
            assertTrue(order.get(1).isNull());
            assertEquals("18000", order.get(6).textValue());
            assertEquals("SNAPSHOT", order.get(7).textValue());
            assertTrue(details.stream().allMatch(row -> row.get(2).isNull()));

            // The header and first item have been inserted when the second item's PK collides.
            ArrayNode duplicateDetail = details.get(1).deepCopy();
            duplicateDetail.set(0, beforeDetails.values().iterator().next().get(0));
            assertEquals(1062, assertThrows(SQLException.class,
                    () -> copyOrder(legacy, order, List.of(details.get(0), duplicateDetail))).getErrorCode());
            assertEquals(beforeOrders, legacyRows(mysql, ORDERS));
            assertEquals(beforeDetails, legacyRows(mysql, DETAILS));

            copyOrder(legacy, order, details);
            var expectedOrders = new TreeMap<>(beforeOrders);
            var expectedDetails = new TreeMap<>(beforeDetails);
            assertNull(expectedOrders.put(orderId, order));
            for (var detail : details) {
                assertNull(expectedDetails.put(Integer.parseInt(detail.get(0).textValue()), detail));
            }
            assertEquals(expectedOrders, legacyRows(mysql, ORDERS));
            assertEquals(expectedDetails, legacyRows(mysql, DETAILS));
            assertEquals(1062, assertThrows(SQLException.class, () -> copyOrder(legacy, order, details)).getErrorCode());
            assertEquals(expectedOrders, legacyRows(mysql, ORDERS));
            assertEquals(expectedDetails, legacyRows(mysql, DETAILS));

            try (var sql = mysql.createStatement()) {
                sql.execute("SET SESSION information_schema_stats_expiry=0");
            }
            var pgStates = SourceImportIntegrationTests.sequences(pg);
            for (var shared : List.of(ORDERS, DETAILS)) {
                long next = Long.parseLong(pgStates.get(shared.name()).split(":")[0]) + 1;
                assertEquals(next, SourceImportIntegrationTests.number(mysql,
                        "SELECT AUTO_INCREMENT FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='" + shared.name() + "'"));
            }
        }

        // The separate read-only MySQL source still matches every accepted row and all seven highwaters.
        try (var current = new SourceSession(original, source.version())) {
            SourceImportTool.compareSource(current, source);
            current.finish();
        }
    }

    private static SourceContract.Table table(String name) {
        return SourceContract.TABLES.stream().filter(table -> table.name().equals(name)).findFirst().orElseThrow();
    }

    private static int authoredOrder(Connection pg, String instant) throws SQLException {
        try (var sql = pg.prepareStatement("""
                INSERT INTO pubburi.t_order (user_id,order_type,order_time,total_price,price_basis)
                VALUES (NULL,'DEMO',?,18000,'SNAPSHOT') RETURNING o_id
                """)) {
            sql.setObject(1, Instant.parse(instant).atOffset(ZoneOffset.UTC));
            try (var rows = sql.executeQuery()) {
                assertTrue(rows.next());
                return rows.getInt(1);
            }
        }
    }

    private static List<JsonNode> pgRows(Connection pg, SourceContract.Table table, String column, int id) throws SQLException {
        var result = new ArrayList<JsonNode>();
        try (var sql = pg.prepareStatement("SELECT " + SourceImportTool.columns(table) + " FROM pubburi."
                + table.name() + " WHERE " + column + "=? ORDER BY " + table.primary().get(0))) {
            sql.setInt(1, id);
            try (var rows = sql.executeQuery()) {
                while (rows.next()) result.add(SourceContract.JSON.valueToTree(TargetValues.row(table, rows)));
            }
        }
        return result;
    }

    private static Map<Integer, JsonNode> legacyRows(Connection mysql, SourceContract.Table table) throws SQLException {
        var result = new TreeMap<Integer, JsonNode>();
        try (var sql = mysql.createStatement(); var rows = sql.executeQuery("SELECT " + mysqlColumns(table) + " FROM " + table.name())) {
            while (rows.next()) result.put(rows.getInt(1), SourceContract.JSON.valueToTree(SourceContract.row(table, rows)));
        }
        return result;
    }

    private static String mysqlColumns(SourceContract.Table table) {
        return String.join(",", table.columns().stream().map(column -> '`' + column.name() + '`').toList());
    }

    private static void copyOrder(SourceSettings legacy, JsonNode order, List<JsonNode> details) throws Exception {
        ImportTransaction.run(legacy.connect(), mysql -> {
            SourceSession.guard(mysql, legacy);
            insert(mysql, ORDERS, order);
            for (var detail : details) insert(mysql, DETAILS, detail);
            return null;
        });
    }

    private static void insert(Connection mysql, SourceContract.Table table, JsonNode row) throws SQLException {
        String markers = String.join(",", Collections.nCopies(table.columns().size(), "?"));
        try (var sql = mysql.prepareStatement("INSERT INTO " + table.name() + " (" + mysqlColumns(table) + ") VALUES (" + markers + ")")) {
            for (int index = 0; index < table.columns().size(); index++) {
                var column = table.columns().get(index);
                var value = row.get(index);
                SourceContract.validate(column, value);
                int parameter = index + 1;
                if (value.isNull()) {
                    sql.setNull(parameter, Types.NULL);
                } else {
                    switch (column.kind()) {
                        case "TEXT" -> sql.setString(parameter, value.textValue());
                        case "I32" -> sql.setInt(parameter, Integer.parseInt(value.textValue()));
                        case "INSTANT" -> sql.setObject(parameter, LocalDateTime.ofInstant(Instant.parse(value.textValue()), ZoneOffset.UTC));
                        default -> throw new IllegalArgumentException("legacy_order_cell_kind_rejected");
                    }
                }
            }
            if (sql.executeUpdate() != 1) throw new IllegalArgumentException("legacy_order_insert_incomplete");
        }
    }
}
