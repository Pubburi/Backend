package com.pubburi.migration;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A stopped-writer import step; never infer a source watermark only from surviving rows. */
final class IdentityReservations {
    record Identity(String table, String column, long maximum) {
        String sequence() { return "pubburi." + table + "_" + column + "_seq"; }
    }

    static final List<Identity> IDENTITIES = List.of(new Identity("t_product", "id", Integer.MAX_VALUE),
            new Identity("t_order", "o_id", Integer.MAX_VALUE), new Identity("t_order_detail", "d_id", Integer.MAX_VALUE),
            new Identity("t_comment", "id", Integer.MAX_VALUE), new Identity("t_stamp", "id", Integer.MAX_VALUE),
            new Identity("t_market", "id", Integer.MAX_VALUE), new Identity("t_product_search_outbox", "event_id", Long.MAX_VALUE));

    private IdentityReservations() { }

    static Map<String, Long> read(Path path) throws IOException {
        if (!path.toAbsolutePath().normalize().toString().contains(java.io.File.separator + ".pubburi" + java.io.File.separator))
            throw new IllegalArgumentException("postgresql_private_identity_manifest_required");
        byte[] bytes;
        try (var input = Files.newInputStream(path)) { bytes = input.readNBytes(8193); }
        if (bytes.length > 8192) throw new IllegalArgumentException("postgresql_identity_manifest_too_large");
        String text = StandardCharsets.US_ASCII.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        List<String> lines = text.lines().toList();
        if (lines.isEmpty() || !"pubburi-identities-v1".equals(lines.get(0)))
            throw new IllegalArgumentException("postgresql_identity_manifest_invalid");
        Map<String, Long> values = new LinkedHashMap<>();
        for (String line : lines.subList(1, lines.size())) {
            if (!line.matches("t_[a-z_]+=[1-9][0-9]{0,18}"))
                throw new IllegalArgumentException("postgresql_identity_manifest_invalid");
            String[] pair = line.split("=", -1);
            long value = Long.parseLong(pair[1]);
            if (values.putIfAbsent(pair[0], value) != null)
                throw new IllegalArgumentException("postgresql_duplicate_identity_reservation");
        }
        if (!values.keySet().equals(IDENTITIES.stream().map(Identity::table).collect(java.util.stream.Collectors.toSet())))
            throw new IllegalArgumentException("postgresql_complete_identity_manifest_required");
        for (Identity identity : IDENTITIES)
            if (values.get(identity.table()) > identity.maximum())
                throw new IllegalArgumentException("postgresql_identity_range_exceeded");
        return values;
    }

    static int apply(PostgresSchemaTool.Settings settings, Map<String, Long> reservations) throws SQLException {
        try (Connection connection = settings.connect()) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                // All identifiers below are static owned-schema identifiers, never manifest-provided SQL.
                statement.execute("LOCK TABLE pubburi.t_product, pubburi.t_order, pubburi.t_order_detail, pubburi.t_comment, "
                        + "pubburi.t_stamp, pubburi.t_market, pubburi.t_product_search_outbox, pubburi.t_product_search_version IN ACCESS EXCLUSIVE MODE");
                Map<Identity, Long> advances = new LinkedHashMap<>();
                for (Identity identity : IDENTITIES) {
                    long requested = reservations.get(identity.table());
                    long maximum;
                    try (ResultSet result = statement.executeQuery("SELECT COALESCE(MAX(" + identity.column() + "),0) FROM pubburi." + identity.table())) {
                        result.next(); maximum = result.getLong(1);
                    }
                    if (identity.table().equals("t_product")) {
                        try (ResultSet result = statement.executeQuery("SELECT COALESCE(MAX(product_id),0) FROM pubburi.t_product_search_version")) {
                            result.next(); maximum = Math.max(maximum, result.getLong(1));
                        }
                    }
                    long next;
                    try (ResultSet result = statement.executeQuery("SELECT last_value,is_called FROM " + identity.sequence())) {
                        result.next(); next = result.getLong(1);
                        if (result.getBoolean(2)) next = Math.addExact(next, 1);
                    }
                    if (requested <= maximum || requested > identity.maximum() || next > identity.maximum())
                        throw new IllegalArgumentException("postgresql_identity_reservation_not_above_retained_ids");
                    if (requested > next) advances.put(identity, requested); // Never move a target sequence backwards.
                }
                for (var advance : advances.entrySet())
                    statement.execute("ALTER SEQUENCE " + advance.getKey().sequence() + " RESTART WITH " + advance.getValue());
                connection.commit(); return advances.size();
            } catch (SQLException | RuntimeException error) {
                connection.rollback(); throw error;
            }
        }
    }
}
