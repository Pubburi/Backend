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

/** Copies only authored order/review slices into a separately fenced legacy MySQL fixture. */
final class LegacyReverseRehearsal {
    private static final SourceContract.Table ORIGINAL_ORDERS = table("t_order");
    private static final SourceContract.Table ORDERS = new SourceContract.Table(ORIGINAL_ORDERS.name(),
            ORIGINAL_ORDERS.columns().stream().map(column -> column.name().equals("order_time")
                    ? new SourceContract.Column(column.name(), column.kind(), column.nullable(), column.sqlType(), "6")
                    : column).toList(), ORIGINAL_ORDERS.primary());
    private static final SourceContract.Table DETAILS = table("t_order_detail");
    private static final SourceContract.Table STAMPS = table("t_stamp");
    private static final SourceContract.Table COMMENTS = table("t_comment");
    private static final String ORDER_TIME = "2026-11-15T04:00:00.123456Z";

    private record AwardedOrder(int id, String user, int before, int after) { }

    private LegacyReverseRehearsal() { }

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
                    () -> copyOrder(legacy, order, List.of(details.get(0), duplicateDetail), List.of(), null)).getErrorCode());
            assertEquals(beforeOrders, legacyRows(mysql, ORDERS));
            assertEquals(beforeDetails, legacyRows(mysql, DETAILS));

            copyOrder(legacy, order, details, List.of(), null);
            var expectedOrders = new TreeMap<>(beforeOrders);
            var expectedDetails = new TreeMap<>(beforeDetails);
            assertNull(expectedOrders.put(orderId, order));
            for (var detail : details) {
                assertNull(expectedDetails.put(Integer.parseInt(detail.get(0).textValue()), detail));
            }
            assertEquals(expectedOrders, legacyRows(mysql, ORDERS));
            assertEquals(expectedDetails, legacyRows(mysql, DETAILS));
            assertEquals(1062, assertThrows(SQLException.class, () -> copyOrder(legacy, order, details, List.of(), null)).getErrorCode());
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
            verifyReviews(pg, legacy, mysql);
        }

        // The separate read-only MySQL source still matches every accepted row and all seven highwaters.
        try (var current = new SourceSession(original, source.version())) {
            assertOrderTimePrecision(current.connection, 0);
            SourceImportTool.compareSource(current, source);
            current.finish();
        }
    }

    private static void verifyReviews(Connection pg, SourceSettings legacy, Connection mysql) throws Exception {
        try (var sql = mysql.createStatement(); var rows = sql.executeQuery("""
                SELECT c.COLLATION_NAME,k.PAD_ATTRIBUTE FROM information_schema.COLUMNS c
                JOIN information_schema.COLLATIONS k ON k.COLLATION_NAME=c.COLLATION_NAME
                WHERE c.TABLE_SCHEMA=DATABASE() AND c.TABLE_NAME='t_comment_taste_tag' AND c.COLUMN_NAME='tag'
                """)) {
            assertTrue(rows.next());
            assertEquals("utf8mb4_bin", rows.getString(1));
            assertEquals("PAD SPACE", rows.getString(2));
        }
        try (var sql = mysql.createStatement(); var rows = sql.executeQuery("""
                SELECT GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX SEPARATOR ',')
                FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE()
                  AND TABLE_NAME='t_comment_taste_tag' AND INDEX_NAME='PRIMARY'
                """)) {
            assertTrue(rows.next());
            assertEquals("comment_id,tag", rows.getString(1));
        }
        assertEquals(0, SourceImportIntegrationTests.number(pg, "SELECT ('dry' COLLATE \"C\" = 'dry ' COLLATE \"C\")::int"));
        var beforeReviews = reviewRows(mysql, COMMENTS, false);
        var beforeTags = reviewRows(mysql, SourceContract.REVIEW_TAGS, false);
        assertFalse(beforeReviews.isEmpty());
        assertFalse(beforeTags.isEmpty());
        var sourceReviews = reviewRows(pg, COMMENTS, true);
        var sourceTags = reviewRows(pg, SourceContract.REVIEW_TAGS, true);
        var privateBefore = privateState(pg);

        int incompatible = authoredReview(pg, List.of("dry", "dry "));
        var rawTags = List.of("  SWEET  ", "Dry,Fruit", "Line\nBreak", "🌾".repeat(40));
        int compatible = authoredReview(pg, rawTags);
        var rejected = pgRows(pg, COMMENTS, "id", incompatible).get(0);
        var rejectedTags = pgRows(pg, SourceContract.REVIEW_TAGS, "comment_id", incompatible);
        var review = pgRows(pg, COMMENTS, "id", compatible).get(0);
        var tags = pgRows(pg, SourceContract.REVIEW_TAGS, "comment_id", compatible);
        assertFalse(beforeReviews.containsKey(TargetValues.key(COMMENTS, rejected)));
        assertFalse(beforeReviews.containsKey(TargetValues.key(COMMENTS, review)));
        assertEquals(2, rejectedTags.size());
        assertEquals(rawTags.size(), tags.size());
        assertEquals(java.util.Set.copyOf(rawTags), tags.stream().map(row -> row.get(1).textValue())
                .collect(java.util.stream.Collectors.toSet()));
        assertEquals(String.format(java.util.Locale.ROOT, "%08x", Float.floatToRawIntBits(3.7f)), review.get(3).textValue());
        assertEquals("원문 후기\n🌾", review.get(4).textValue());
        assertNull(sourceReviews.put(TargetValues.key(COMMENTS, rejected), rejected));
        assertNull(sourceReviews.put(TargetValues.key(COMMENTS, review), review));
        for (var tag : rejectedTags) assertNull(sourceTags.put(TargetValues.key(SourceContract.REVIEW_TAGS, tag), tag));
        for (var tag : tags) assertNull(sourceTags.put(TargetValues.key(SourceContract.REVIEW_TAGS, tag), tag));
        assertEquals(sourceReviews, reviewRows(pg, COMMENTS, true));
        assertEquals(sourceTags, reviewRows(pg, SourceContract.REVIEW_TAGS, true));

        // PG C retains both values. The legacy PAD SPACE key rejects the second
        // child after the new header and first child were inserted in this TX.
        assertEquals(1062, assertThrows(SQLException.class,
                () -> copyReview(legacy, rejected, rejectedTags)).getErrorCode());
        assertEquals(beforeReviews, reviewRows(mysql, COMMENTS, false));
        assertEquals(beforeTags, reviewRows(mysql, SourceContract.REVIEW_TAGS, false));
        assertEquals(sourceReviews, reviewRows(pg, COMMENTS, true));
        assertEquals(sourceTags, reviewRows(pg, SourceContract.REVIEW_TAGS, true));

        copyReview(legacy, review, tags);
        var expectedReviews = new TreeMap<>(beforeReviews);
        var expectedTags = new TreeMap<>(beforeTags);
        assertNull(expectedReviews.put(TargetValues.key(COMMENTS, review), review));
        for (var tag : tags) assertNull(expectedTags.put(TargetValues.key(SourceContract.REVIEW_TAGS, tag), tag));
        assertEquals(expectedReviews, reviewRows(mysql, COMMENTS, false));
        assertEquals(expectedTags, reviewRows(mysql, SourceContract.REVIEW_TAGS, false));
        assertEquals(1062, assertThrows(SQLException.class, () -> copyReview(legacy, review, tags)).getErrorCode());
        assertEquals(expectedReviews, reviewRows(mysql, COMMENTS, false));
        assertEquals(expectedTags, reviewRows(mysql, SourceContract.REVIEW_TAGS, false));

        long next = SourceImportIntegrationTests.number(mysql,
                "SELECT AUTO_INCREMENT FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_comment'");
        assertTrue(next > compatible && next > incompatible);
        // Failed InnoDB inserts may consume IDs. Observe the actual highwater,
        // then prove a normal next insert never reuses either authored ID.
        try (var sql = mysql.prepareStatement("INSERT INTO t_comment(user_id,product_id,rating,comment) VALUES(?,?,?,?)",
                java.sql.Statement.RETURN_GENERATED_KEYS)) {
            sql.setString(1, review.get(1).textValue());
            sql.setInt(2, Integer.parseInt(review.get(2).textValue()));
            sql.setFloat(3, 3.7f);
            sql.setString(4, "Authored next legacy identity");
            assertEquals(1, sql.executeUpdate());
            try (var keys = sql.getGeneratedKeys()) {
                assertTrue(keys.next());
                assertEquals(next, keys.getLong(1));
                ArrayNode inserted = review.deepCopy();
                inserted.set(0, SourceContract.JSON.valueToTree(Long.toString(next)));
                inserted.set(4, SourceContract.JSON.valueToTree("Authored next legacy identity"));
                assertNull(expectedReviews.put(TargetValues.key(COMMENTS, inserted), inserted));
            }
        }
        assertEquals(expectedReviews, reviewRows(mysql, COMMENTS, false));
        assertEquals(expectedTags, reviewRows(mysql, SourceContract.REVIEW_TAGS, false));
        assertEquals(sourceReviews, reviewRows(pg, COMMENTS, true));
        assertEquals(sourceTags, reviewRows(pg, SourceContract.REVIEW_TAGS, true));
        assertEquals(privateBefore, privateState(pg));
    }

    private static int authoredReview(Connection pg, List<String> tags) throws SQLException {
        int id;
        try (var sql = pg.prepareStatement("""
                INSERT INTO pubburi.t_comment(user_id,product_id,rating,comment)
                SELECT u.id,p.id,?,? FROM
                  (SELECT id FROM pubburi.t_user ORDER BY id LIMIT 1) u CROSS JOIN
                  (SELECT id FROM pubburi.t_product ORDER BY id LIMIT 1) p RETURNING id
                """)) {
            sql.setFloat(1, 3.7f);
            sql.setString(2, "원문 후기\n🌾");
            try (var rows = sql.executeQuery()) { assertTrue(rows.next()); id = rows.getInt(1); }
        }
        try (var sql = pg.prepareStatement("INSERT INTO pubburi.t_comment_taste_tag(comment_id,tag) VALUES(?,?)")) {
            for (String tag : tags) { sql.setInt(1, id); sql.setString(2, tag); assertEquals(1, sql.executeUpdate()); }
        }
        return id;
    }

    private static Map<String, JsonNode> reviewRows(Connection connection, SourceContract.Table table, boolean pg) throws SQLException {
        var result = new TreeMap<String, JsonNode>();
        String columns = pg ? SourceImportTool.columns(table) : mysqlColumns(table);
        try (var sql = connection.createStatement(); var rows = sql.executeQuery(
                "SELECT " + columns + " FROM " + (pg ? "pubburi." : "") + table.name())) {
            while (rows.next()) {
                var row = SourceContract.JSON.valueToTree(pg ? TargetValues.row(table, rows) : SourceContract.row(table, rows));
                assertNull(result.put(TargetValues.key(table, row), row));
            }
        }
        return result;
    }

    private static Map<String, List<String>> privateState(Connection pg) throws SQLException {
        var result = new TreeMap<String, List<String>>();
        for (String table : TargetSchemaContract.POSTGRES_ONLY_TABLES) {
            var values = new ArrayList<String>();
            try (var sql = pg.createStatement(); var rows = sql.executeQuery(
                    "SELECT row_to_json(t)::text FROM pubburi." + table + " t ORDER BY row_to_json(t)::text COLLATE \"C\"")) {
                while (rows.next()) values.add(rows.getString(1));
            }
            result.put(table, values);
        }
        return result;
    }

    private static void copyReview(SourceSettings legacy, JsonNode review, List<JsonNode> tags) throws Exception {
        ImportTransaction.run(legacy.connect(), mysql -> {
            SourceSession.guard(mysql, legacy);
            insert(mysql, COMMENTS, review);
            for (var tag : tags) insert(mysql, SourceContract.REVIEW_TAGS, tag);
            return null;
        });
    }

    private static void verifyLinkedOrder(Connection pg, SourceSettings legacy, Connection mysql,
            Map<Integer, JsonNode> expectedOrders, Map<Integer, JsonNode> expectedDetails) throws Exception {
        var expectedStamps = legacyRows(mysql, STAMPS);
        assertFalse(expectedStamps.isEmpty());
        var award = authoredLinkedOrder(pg);
        int orderId = award.id();
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
        assertEquals(award.user(), order.get(1).textValue());
        assertEquals(award.before() + stamp.get(3).asInt(), award.after());
        assertEquals(award.after(), balance(pg, "pubburi.t_user", award.user()));
        assertEquals(award.before(), balance(mysql, "t_user", award.user()));

        // The order and detail have been inserted when the final stamp ID collides.
        ArrayNode duplicateStamp = stamp.deepCopy();
        duplicateStamp.set(0, expectedStamps.values().iterator().next().get(0));
        assertEquals(1062, assertThrows(SQLException.class,
                () -> copyOrder(legacy, order, details, List.of(duplicateStamp), award)).getErrorCode());
        assertEquals(expectedOrders, legacyRows(mysql, ORDERS));
        assertEquals(expectedDetails, legacyRows(mysql, DETAILS));
        assertEquals(expectedStamps, legacyRows(mysql, STAMPS));
        assertEquals(award.before(), balance(mysql, "t_user", award.user()));

        // The target independently moved. Reject after writing all three rows and retain its balance.
        int changedBalance = award.after() + 1;
        replaceBalance(mysql, award.user(), award.before(), changedBalance);
        try {
            assertEquals("legacy_order_stamp_balance_changed", assertThrows(IllegalStateException.class,
                    () -> copyOrder(legacy, order, details, stamps, award)).getMessage());
            assertEquals(expectedOrders, legacyRows(mysql, ORDERS));
            assertEquals(expectedDetails, legacyRows(mysql, DETAILS));
            assertEquals(expectedStamps, legacyRows(mysql, STAMPS));
            assertEquals(changedBalance, balance(mysql, "t_user", award.user()));
            assertEquals(award.after(), balance(pg, "pubburi.t_user", award.user()));
        } finally {
            replaceBalance(mysql, award.user(), changedBalance, award.before());
        }

        copyOrder(legacy, order, details, stamps, award);
        assertNull(expectedOrders.put(orderId, order));
        assertNull(expectedDetails.put(Integer.parseInt(detail.get(0).textValue()), detail));
        assertNull(expectedStamps.put(Integer.parseInt(stamp.get(0).textValue()), stamp));
        assertEquals(expectedOrders, legacyRows(mysql, ORDERS));
        assertEquals(expectedDetails, legacyRows(mysql, DETAILS));
        assertEquals(expectedStamps, legacyRows(mysql, STAMPS));
        assertEquals(award.after(), balance(mysql, "t_user", award.user()));
        assertEquals(1062, assertThrows(SQLException.class,
                () -> copyOrder(legacy, order, details, stamps, award)).getErrorCode());
        assertEquals(award.after(), balance(mysql, "t_user", award.user()));
    }

    private static AwardedOrder authoredLinkedOrder(Connection pg) throws SQLException {
        try (var sql = pg.prepareStatement("""
                WITH picked_product AS (SELECT * FROM pubburi.t_product WHERE price > 0 ORDER BY id LIMIT 1),
                picked_customer AS (SELECT id,stamps FROM pubburi.t_user WHERE role='USER' ORDER BY id LIMIT 1),
                placed_order AS (
                    INSERT INTO pubburi.t_order(user_id,order_type,order_time,total_price,price_basis)
                    SELECT u.id,'TAKEOUT',?,p.price,'ORDER_SNAPSHOT'
                    FROM picked_customer u CROSS JOIN picked_product p
                    RETURNING o_id,user_id
                ), order_line AS (
                    INSERT INTO pubburi.t_order_detail
                        (order_id,product_id,quantity,product_name,product_type,product_img,unit_price,sum_price)
                    SELECT o.o_id,p.id,1,p.name,p.type,p.img,p.price,p.price FROM placed_order o CROSS JOIN picked_product p
                    RETURNING order_id,quantity
                ), awarded AS (
                    INSERT INTO pubburi.t_stamp(user_id,order_id,quantity)
                    SELECT o.user_id,l.order_id,l.quantity FROM placed_order o JOIN order_line l ON l.order_id=o.o_id
                    RETURNING user_id,order_id,quantity
                )
                UPDATE pubburi.t_user u SET stamps=u.stamps+a.quantity
                FROM awarded a JOIN picked_customer c ON c.id=a.user_id WHERE u.id=a.user_id
                RETURNING a.order_id,u.id,c.stamps,u.stamps
                """)) {
            sql.setObject(1, Instant.parse(ORDER_TIME).atOffset(ZoneOffset.UTC));
            try (var rows = sql.executeQuery()) {
                assertTrue(rows.next(), "Authored linked order requires an existing customer and product");
                return new AwardedOrder(rows.getInt(1), rows.getString(2), rows.getInt(3), rows.getInt(4));
            }
        }
    }

    private static int balance(Connection connection, String table, String user) throws SQLException {
        try (var sql = connection.prepareStatement("SELECT stamps FROM " + table + " WHERE id=?")) {
            sql.setString(1, user);
            try (var rows = sql.executeQuery()) {
                assertTrue(rows.next(), "Authored customer balance is missing");
                return rows.getInt(1);
            }
        }
    }

    private static void replaceBalance(Connection mysql, String user, int before, int after) throws SQLException {
        try (var sql = mysql.prepareStatement("UPDATE t_user SET stamps=? WHERE id=? AND stamps=?")) {
            sql.setInt(1, after);
            sql.setString(2, user);
            sql.setInt(3, before);
            if (sql.executeUpdate() != 1) throw new IllegalStateException("legacy_order_stamp_balance_changed");
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
            List<JsonNode> stamps, AwardedOrder award) throws Exception {
        ImportTransaction.run(legacy.connect(), mysql -> {
            SourceSession.guard(mysql, legacy);
            insert(mysql, ORDERS, order);
            for (var detail : details) insert(mysql, DETAILS, detail);
            for (var stamp : stamps) insert(mysql, STAMPS, stamp);
            if (award != null) replaceBalance(mysql, award.user(), award.before(), award.after());
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
                        case "F32" -> sql.setFloat(parameter, Float.intBitsToFloat(Integer.parseUnsignedInt(value.textValue(), 16)));
                        case "INSTANT" -> sql.setObject(parameter, LocalDateTime.ofInstant(Instant.parse(value.textValue()), ZoneOffset.UTC));
                        default -> throw new IllegalArgumentException("legacy_order_cell_kind_rejected");
                    }
                }
            }
            if (sql.executeUpdate() != 1) throw new IllegalArgumentException("legacy_order_insert_incomplete");
        }
    }
}
