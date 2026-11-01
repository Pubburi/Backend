package com.pubburi.migration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;

/** Actual source SHOW INDEX metadata, not assumed automatic PostgreSQL FK indexes. */
final class SchemaIndexContract {
    record Key(String table, boolean unique, String columns) { }
    private SchemaIndexContract() { }

    static Set<Key> expected() throws IOException {
        try(var input=SchemaIndexContract.class.getResourceAsStream("/source-index-contract.tsv")) {
            if(input==null) throw new IllegalStateException("postgresql_index_contract_missing");
            Set<Key> result=new HashSet<>();
            for(String line:new String(input.readAllBytes(),StandardCharsets.UTF_8).lines().toList()) {
                String[] parts=line.split("\t",-1);
                if(parts.length!=3 || !Set.of("U","N").contains(parts[1])) throw new IllegalStateException("postgresql_index_contract_invalid");
                result.add(new Key(parts[0],parts[1].equals("U"),parts[2]));
            }
            return result;
        }
    }

    static Set<Key> actual(Connection connection) throws SQLException {
        String sql="""
                SELECT t.relname AS table_name,i.indisunique,
                    (SELECT string_agg(a.attname||':'||
                        CASE WHEN (i.indoption[(k.ordinality-1)::int] & 1)=1 THEN 'D' ELSE 'A' END||':'||
                        CASE WHEN a.attnotnull THEN '-' WHEN (i.indoption[(k.ordinality-1)::int] & 2)=2 THEN 'F' ELSE 'L' END,
                        ',' ORDER BY k.ordinality)
                     FROM unnest(i.indkey) WITH ORDINALITY AS k(attnum,ordinality)
                     JOIN pg_attribute a ON a.attrelid=t.oid AND a.attnum=k.attnum
                     WHERE k.ordinality<=i.indnkeyatts) AS columns
                FROM pg_index i JOIN pg_class t ON t.oid=i.indrelid JOIN pg_namespace n ON n.oid=t.relnamespace
                JOIN pg_class ix ON ix.oid=i.indexrelid JOIN pg_am am ON am.oid=ix.relam
                WHERE n.nspname='pubburi' AND t.relname LIKE 't_%' AND am.amname='btree'
                  AND i.indisvalid AND i.indisready AND i.indpred IS NULL AND i.indexprs IS NULL
                """;
        Set<Key> result=new HashSet<>();
        try(Statement statement=connection.createStatement(); ResultSet rows=statement.executeQuery(sql)) {
            while(rows.next()) result.add(new Key(rows.getString("table_name"),rows.getBoolean("indisunique"),rows.getString("columns")));
        }
        return result;
    }

    static void verify(PostgresSchemaTool.Settings settings) throws SQLException,IOException {
        try(Connection connection=settings.connect()) {
            connection.setReadOnly(true);
            if(!actual(connection).containsAll(expected())) throw new IllegalStateException("postgresql_source_indexes_not_preserved");
        }
    }
}
