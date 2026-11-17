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
    private static final SourceContract.Table ORIGINAL_ORDERS = table("t_order");
    private static final SourceContract.Table ORDERS = new SourceContract.Table(ORIGINAL_ORDERS.name(),
            ORIGINAL_ORDERS.columns().stream().map(column -> column.name().equals("order_time")
                    ? new SourceContract.Column(column.name(), column.kind(), column.nullable(), column.sqlType(), "6")
                    : column).toList(), ORIGINAL_ORDERS.primary());
    private static final SourceContract.Table DETAILS = table("t_order_detail");
    private static final SourceContract.Table STAMPS = table("t_stamp");
    private static final String ORDER_TIME = "2026-11-15T04:00:00.123456Z";

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
            assertOrderTimePrecision(mysql, 6);
            var beforeOrders = legacyRows(mysql, ORDERS);
            var beforeDetails = legacyRows(mysql, DETAILS);
            assertFalse(beforeOrders.isEmpty());
            assertFalse(beforeDetails.isEmpty());

            int orderId = authoredOrder(pg, ORDER_TIME);
            assertEquals("source_typed_value_invalid", assertThrows(IllegalArgumentException.class,
                    () -> pgRows(pg, ORIGINAL_ORDERS, "o_id", orderId)).getMessage());
            assertEquals(beforeOrders, legacyRows(mysql, ORDERS));
            assertEquals(beforeDetails, legacyRows(mysql, DETAILS));

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
            assertEquals(ORDER_TIME, order.get(4).textValue());
            assertEquals("18000", order.get(6).textValue());
            assertEquals("SNAPSHOT", order.get(7).textValue());
            assertTrue(details.stream().allMatch(row -> row.get(2).isNull()));

            // The header and first item have been inserted when the second item's PK collides.
            ArrayNode duplicateDetail = details.get(1).deepCopy();
            duplicateDetail.set(0, beforeDetails.values().iterator().next().get(0));
            assertEquals(1062, assertThrows(SQLException.class,
                    () -> copyOrder(legacy, order, List.of(details.get(0), duplicateDetail), List.of())).getErrorCode());
            assertEquals(beforeOrders, legacyRows(mysql, ORDERS));
            assertEquals(beforeDetails, legacyRows(mysql, DETAILS));

            copyOrder(legacy, order, details, List.of());
            var expectedOrders = new TreeMap<>(beforeOrders);
            var expectedDetails = new TreeMap<>(beforeDetails);
            assertNull(expectedOrders.put(orderId, order));
            for (var detail : details) {
                assertNull(expectedDetails.put(Integer.parseInt(detail.get(0).textValue()), detail));
            }
            assertEquals(expectedOrders, legacyRows(mysql, ORDERS));
            assertEquals(expectedDetails, legacyRows(mysql, DETAILS));
            assertEquals(1062, assertThrows(SQLException.class, () -> copyOrder(legacy, order, details, List.of())).getErrorCode());
            assertEquals(expectedOrders, legacyRows(mysql, ORDERS));
            assertEquals(expectedDetails, legacyRows(mysql, DETAILS));

            verifyLinkedOrder(pg, legacy, mysql, expectedOrders, expectedDetails);
            try (var sql = mysql.createStatement()) {
                sql.execute("SET SESSION information_schema_stats_expiry=0");
            }
            var pgStates = SourceImportIntegrationTests.sequences(pg);
            for (var shared : List.of(ORDERS, DETAILS, STAMPS)) {
                long next = Long.parseLong(pgStates.get(shared.name()).split(":")[0]) + 1;
                assertEquals(next, SourceImportIntegrationTests.number(mysql,
                        "SELECT AUTO_INCREMENT FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='" + shared.name() + "'"));
            }
        }

        // The separate read-only MySQL source still matches every accepted row and all seven highwaters.
        try (var current = new SourceSession(original, source.version())) {
            assertOrderTimePrecision(current.connection, 0);
            SourceImportTool.compareSource(current, source);
            current.finish();
        }
    }

    private static void verifyLinkedOrder(Connection pg, SourceSettings legacy, Connection mysql,
            Map<Integer, JsonNode> expectedOrders, Map<Integer, JsonNode> expectedDetails) throws Exception {
        var expectedStamps = legacyRows(mysql, STAMPS);
        assertFalse(expectedStamps.isEmpty());
        int orderId = authoredLinkedOrder(pg);
        var order = pgRows(pg, ORDERS, "o_id", orderId).get(0);
        var details = pgRows(pg, DETAILS, "order_id", orderId);
        var stamps = pgRows(pg, STAMPS, "order_id", orderId);
        assertEquals(1, details.size());
        assertEquals(1, stamps.size());
        var detail = details.get(0);
        var stamp = stamps.get(0);
        assertTrue(order.get(1).isTextual());
        assertEquals(order.get(1), stamp.get(1));
        assertEquals(order.get(0), detail.get(1));
        assertEquals(order.get(0), stamp.get(2));
        assertFalse(detail.get(2).isNull());
        assertEquals(stamp.get(3), detail.get(3));
        assertEquals(order.get(6), detail.get(8));

        // The order and detail have been inserted when the final stamp ID collides.
        ArrayNode duplicateStamp = stamp.deepCopy();
        duplicateStamp.set(0, expectedStamps.values().iterator().next().get(0));
        assertEquals(1062, assertThrows(SQLException.class,
                () -> copyOrder(legacy, order, details, List.of(duplicateStamp))).getErrorCode());
        assertEquals(expectedOrders, legacyRows(mysql, ORDERS));
        assertEquals(expectedDetails, legacyRows(mysql, DETAILS));
        assertEquals(expectedStamps, legacyRows(mysql, STAMPS));

        copyOrder(legacy, order, details, stamps);
        assertNull(expectedOrders.put(orderId, order));
        assertNull(expectedDetails.put(Integer.parseInt(detail.get(0).textValue()), detail));
        assertNull(expectedStamps.put(Integer.parseInt(stamp.get(0).textValue()), stamp));
        assertEquals(expectedOrders, legacyRows(mysql, ORDERS));
        assertEquals(expectedDetails, legacyRows(mysql, DETAILS));
        assertEquals(expectedStamps, legacyRows(mysql, STAMPS));
    }

    private static int authoredLinkedOrder(Connection pg) throws SQLException {
        try (var sql = pg.prepareStatement("""
                WITH picked_product AS (SELECT * FROM pubburi.t_product WHERE price > 0 ORDER BY id LIMIT 1),
                placed_order AS (
                    INSERT INTO pubburi.t_order(user_id,order_type,order_time,total_price,price_basis)
                    SELECT u.id,'TAKEOUT',?,p.price,'ORDER_SNAPSHOT'
                    FROM (SELECT id FROM pubburi.t_user WHERE role='USER' ORDER BY id LIMIT 1) u CROSS JOIN picked_product p
                    RETURNING o_id,user_id
                ), order_line AS (
                    INSERT INTO pubburi.t_order_detail
                        (order_id,product_id,quantity,product_name,product_type,product_img,unit_price,sum_price)
                    SELECT o.o_id,p.id,1,p.name,p.type,p.img,p.price,p.price FROM placed_order o CROSS JOIN picked_product p
                    RETURNING order_id,quantity
                )
                INSERT INTO pubburi.t_stamp(user_id,order_id,quantity)
                SELECT o.user_id,l.order_id,l.quantity FROM placed_order o JOIN order_line l ON l.order_id=o.o_id
                RETURNING order_id
                """)) {
            sql.setObject(1, Instant.parse(ORDER_TIME).atOffset(ZoneOffset.UTC));
            try (var rows = sql.executeQuery()) {
                assertTrue(rows.next(), "Authored linked order requires an existing customer and product");
                return rows.getInt(1);
            }
        }
    }

    private static SourceContract.Table table(String name) {
        return SourceContract.TABLES.stream().filter(table -> table.name().equals(name)).findFirst().orElseThrow();
    }

    private static void assertOrderTimePrecision(Connection mysql, int expected) throws SQLException {
        assertEquals(1, SourceImportIntegrationTests.number(mysql, """
                SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_order' AND COLUMN_NAME='order_time'
                  AND DATA_TYPE='timestamp' AND IS_NULLABLE='YES' AND DATETIME_PRECISION=
                """ + expected));
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

    private static void copyOrder(SourceSettings legacy, JsonNode order, List<JsonNode> details,
            List<JsonNode> stamps) throws Exception {
        ImportTransaction.run(legacy.connect(), mysql -> {
            SourceSession.guard(mysql, legacy);
            insert(mysql, ORDERS, order);
            for (var detail : details) insert(mysql, DETAILS, detail);
            for (var stamp : stamps) insert(mysql, STAMPS, stamp);
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
