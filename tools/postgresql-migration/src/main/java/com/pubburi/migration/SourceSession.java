package com.pubburi.migration;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** A single read-only InnoDB row snapshot; non-MVCC metadata is checked separately. */
final class SourceSession implements AutoCloseable {
    final SourceSettings settings;
    final Connection connection;
    final String server;
    final Map<String,Long> counts=new LinkedHashMap<>();
    final Map<String,String> textCollations=new LinkedHashMap<>();
    final Map<String,String> next;
    private boolean committed;

    static String guard(Connection c,SourceSettings settings) throws SQLException {
        try(PreparedStatement s=c.prepareStatement("SELECT DATABASE(),@@version,@@server_uuid,@@session.time_zone,"
                +"EXISTS(SELECT 1 FROM pubburi_source_snapshot_guard WHERE token=? AND state='QUIESCED' AND window_uuid=?),@@partial_revokes")) {
            s.setString(1,settings.token());s.setString(2,settings.window());
            try(ResultSet r=s.executeQuery()) {
                if(!r.next() || !settings.database().equals(r.getString(1)) || !r.getString(2).startsWith("8.4.")
                        || !(Set.of("+00:00","UTC").contains(r.getString(4))) || !r.getBoolean(5) || r.getBoolean(6)) throw new IllegalArgumentException("source_guard_rejected");
                String server=r.getString(3);if(!UUID.fromString(server).toString().equals(server)) throw new IllegalArgumentException("source_server_identity_invalid");
                try(Statement ssl=c.createStatement();ResultSet cipher=ssl.executeQuery("SHOW SESSION STATUS LIKE 'Ssl_cipher'")) {
                    if(!cipher.next() || cipher.getString(2).isBlank()) throw new IllegalArgumentException("source_tls_required");
                }
                return server;
            }
        }
    }
    static boolean exactSchemaGrant(String object,String database) {
        // With partial_revokes=OFF, unescaped '_' and '%' are privilege-pattern wildcards,
        // even inside identifier quotes. Do not normalize them before checking literal scope.
        return object.equals("`"+database.replace("_","\\_")+"`.*");
    }
    static void readOnlyGrants(Connection c,SourceSettings settings) throws SQLException {
        boolean select=false;
        try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SHOW GRANTS")) {
            while(r.next()) {
                String grant=r.getString(1);int on=grant.indexOf(" ON "),to=grant.indexOf(" TO ");
                if(!grant.startsWith("GRANT ") || on<0 || to<on || grant.contains("WITH GRANT OPTION")) throw new IllegalArgumentException("source_read_only_grants_required");
                String privileges=grant.substring(6,on),object=grant.substring(on+4,to);
                if(privileges.equals("USAGE") && object.equals("*.*")) continue;
                Set<String> values=Set.of(privileges.split(", "));
                if(!exactSchemaGrant(object,settings.database()) || !Set.of("SELECT","SHOW VIEW").containsAll(values) || !values.contains("SELECT"))
                    throw new IllegalArgumentException("source_read_only_grants_required");
                select=true;
            }
        }
        if(!select) throw new IllegalArgumentException("source_read_only_grants_required");
    }
    SourceSession(SourceSettings settings) throws Exception {
        this.settings=settings;Connection opened=settings.connect();
        try {
            server=guard(opened,settings);readOnlyGrants(opened,settings);
            try(Statement s=opened.createStatement()) {s.execute("SET SESSION information_schema_stats_expiry=0");s.execute("SET SESSION lock_wait_timeout=2");}
            opened.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);opened.setReadOnly(true);opened.setAutoCommit(false);
            try(Statement s=opened.createStatement()) {s.execute("START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY");}
            connection=opened;
            try(Statement s=opened.createStatement();ResultSet r=s.executeQuery("SELECT @@transaction_read_only,@@transaction_isolation,@@session.information_schema_stats_expiry")) {
                if(!r.next() || r.getInt(1)!=1 || !r.getString(2).equals("REPEATABLE-READ") || r.getInt(3)!=0) throw new IllegalArgumentException("source_read_only_snapshot_required");
            }
            // Ordinary reads acquire table metadata locks until this TX ends; no LOCK/RELOAD privilege.
            for(var table:SourceContract.TABLES) try(Statement s=opened.createStatement();ResultSet ignored=s.executeQuery("SELECT `"+table.primary().get(0)+"` FROM `"+table.name()+"` LIMIT 1")) { }
            long total=0;
            for(var table:SourceContract.TABLES) try(Statement s=opened.createStatement();ResultSet r=s.executeQuery("SELECT COUNT(*) FROM (SELECT 1 FROM `"+table.name()+"` LIMIT 10001) AS source_count")) {
                r.next();long n=r.getLong(1);total+=n;if(n<0 || total>SourceContract.MAX_ROWS) throw new IllegalArgumentException("source_row_limit_exceeded");counts.put(table.name(),n);
            }
            metadata();next=highwater();
        } catch(Exception error) {opened.close();throw error;}
    }
    void metadata() throws Exception {
        Set<String> tables=new HashSet<>();
        try(PreparedStatement s=connection.prepareStatement("SELECT TABLE_NAME,ENGINE,TABLE_TYPE FROM information_schema.TABLES WHERE TABLE_SCHEMA=?")) {
            s.setString(1,settings.database());try(ResultSet r=s.executeQuery()) {
                while(r.next()) {if(!"InnoDB".equals(r.getString(2)) || !"BASE TABLE".equals(r.getString(3))) throw new IllegalArgumentException("source_innodb_tables_required");tables.add(r.getString(1));}
            }
        }
        Set<String> expected=new HashSet<>(SourceContract.TABLES.stream().map(SourceContract.Table::name).toList());expected.add("pubburi_source_snapshot_guard");
        if(!tables.equals(expected)) throw new IllegalArgumentException("source_table_set_changed");
        for(var table:SourceContract.TABLES) {
            try(PreparedStatement s=connection.prepareStatement("SELECT COLUMN_NAME,DATA_TYPE,IS_NULLABLE,CHARACTER_MAXIMUM_LENGTH,NUMERIC_PRECISION,NUMERIC_SCALE,DATETIME_PRECISION,COLUMN_TYPE,CHARACTER_SET_NAME,COLLATION_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=? AND TABLE_NAME=? ORDER BY ORDINAL_POSITION")) {
                s.setString(1,settings.database());s.setString(2,table.name());try(ResultSet r=s.executeQuery()) {
                    for(var column:table.columns()) {
                        if(!r.next() || !column.name().equals(r.getString(1)) || !column.sqlType().equals(r.getString(2))
                                || column.nullable()!=r.getString(3).equals("YES") || r.getString(8).contains("unsigned")) throw new IllegalArgumentException("source_column_contract_changed");
                        if(Set.of("varchar","char").contains(column.sqlType())) {
                            if(r.getLong(4)!=Long.parseLong(column.limit())) throw new IllegalArgumentException("source_column_length_changed");
                            String charset=r.getString(9),collation=r.getString(10);
                            if(!Set.of("ascii","utf8mb4").contains(charset) || collation==null || !collation.matches("[a-z0-9_]{1,64}")) throw new IllegalArgumentException("source_text_encoding_unsupported");
                            textCollations.put(table.name()+"."+column.name(),charset+":"+collation);
                        }
                        if(column.kind().equals("DECIMAL")) {String[] p=column.limit().split(",");if(r.getInt(5)!=Integer.parseInt(p[0]) || r.getInt(6)!=Integer.parseInt(p[1])) throw new IllegalArgumentException("source_decimal_contract_changed");}
                        if(column.kind().equals("INSTANT") && r.getInt(7)!=Integer.parseInt(column.limit())) throw new IllegalArgumentException("source_time_precision_changed");
                    }
                    if(r.next()) throw new IllegalArgumentException("source_column_contract_changed");
                }
            }
            for(var column:table.columns()) if(column.kind().equals("JSONRAW")) try(Statement s=connection.createStatement();ResultSet r=s.executeQuery("SELECT COALESCE(MAX(OCTET_LENGTH(`"+column.name()+"`)),0) FROM `"+table.name()+"`")) {
                r.next();if(r.getLong(1)>SourceContract.MAX_CELL_BYTES) throw new IllegalArgumentException("source_cell_too_large");
            }
        }
        Set<SchemaIndexContract.Key> indexes=new HashSet<>();
        try(PreparedStatement s=connection.prepareStatement("SELECT TABLE_NAME,INDEX_NAME,NON_UNIQUE,COLUMN_NAME,COLLATION,NULLABLE,SUB_PART,INDEX_TYPE FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=? AND TABLE_NAME<> 'pubburi_source_snapshot_guard' ORDER BY TABLE_NAME,INDEX_NAME,SEQ_IN_INDEX")) {
            s.setString(1,settings.database());try(ResultSet r=s.executeQuery()) {
                String group=null,table=null,columns="";boolean unique=false;
                while(r.next()) {
                    if(r.getObject(7)!=null || !"BTREE".equals(r.getString(8))) throw new IllegalArgumentException("source_index_contract_changed");
                    String key=r.getString(1)+":"+r.getString(2);
                    if(!key.equals(group)) {if(group!=null) indexes.add(new SchemaIndexContract.Key(table,unique,columns));group=key;table=r.getString(1);unique=r.getInt(3)==0;columns="";}
                    columns+=(columns.isEmpty()?"":",")+r.getString(4)+":"+r.getString(5)+":"+(r.getString(6).equals("YES")?"F":"-");
                }
                if(group!=null) indexes.add(new SchemaIndexContract.Key(table,unique,columns));
            }
        }
        if(!indexes.equals(SchemaIndexContract.expected())) throw new IllegalArgumentException("source_index_contract_changed");
        Map<String,String> primary=new LinkedHashMap<>();
        try(PreparedStatement s=connection.prepareStatement("SELECT TABLE_NAME,COLUMN_NAME FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=? AND INDEX_NAME='PRIMARY' AND TABLE_NAME<>'pubburi_source_snapshot_guard' ORDER BY TABLE_NAME,SEQ_IN_INDEX")) {
            s.setString(1,settings.database());try(ResultSet r=s.executeQuery()) {while(r.next()) primary.merge(r.getString(1),r.getString(2),(a,b)->a+","+b);}
        }
        for(var table:SourceContract.TABLES) if(!String.join(",",table.primary()).equals(primary.get(table.name()))) throw new IllegalArgumentException("source_primary_contract_changed");
        Set<String> foreign=new HashSet<>();
        try(PreparedStatement s=connection.prepareStatement("SELECT k.TABLE_NAME,k.COLUMN_NAME,k.REFERENCED_TABLE_NAME,k.REFERENCED_COLUMN_NAME,r.DELETE_RULE,r.UPDATE_RULE FROM information_schema.KEY_COLUMN_USAGE k JOIN information_schema.REFERENTIAL_CONSTRAINTS r ON r.CONSTRAINT_SCHEMA=k.CONSTRAINT_SCHEMA AND r.CONSTRAINT_NAME=k.CONSTRAINT_NAME AND r.TABLE_NAME=k.TABLE_NAME WHERE k.CONSTRAINT_SCHEMA=? AND k.REFERENCED_TABLE_NAME IS NOT NULL")) {
            s.setString(1,settings.database());try(ResultSet r=s.executeQuery()) {
                while(r.next()) {
                    if(!Set.of("RESTRICT","NO ACTION").contains(r.getString(6))) throw new IllegalArgumentException("source_foreign_contract_changed");
                    foreign.add(r.getString(1)+'\t'+r.getString(2)+'\t'+r.getString(3)+'\t'+r.getString(4)+'\t'+r.getString(5).replace("NO ACTION","RESTRICT"));
                }
            }
        }
        if(!foreign.equals(Set.copyOf(new String(SourceContract.resource("source-foreign-contract.tsv"),StandardCharsets.UTF_8).lines().toList()))) throw new IllegalArgumentException("source_foreign_contract_changed");
        // Check row references with the source engine's own collation, never a Java approximation.
        for(String line:foreign) {
            String[] p=line.split("\t");
            try(Statement s=connection.createStatement();ResultSet r=s.executeQuery("SELECT EXISTS(SELECT 1 FROM `"+p[0]+"` child LEFT JOIN `"+p[2]+"` parent ON child.`"+p[1]+"`=parent.`"+p[3]+"` WHERE child.`"+p[1]+"` IS NOT NULL AND parent.`"+p[3]+"` IS NULL LIMIT 1)")) {
                r.next();if(r.getBoolean(1)) throw new IllegalArgumentException("source_orphan_reference");
            }
        }
    }
    Map<String,String> highwater() throws SQLException {
        Map<String,String> values=new LinkedHashMap<>();
        for(var identity:IdentityReservations.IDENTITIES) try(PreparedStatement s=connection.prepareStatement("SELECT AUTO_INCREMENT FROM information_schema.TABLES WHERE TABLE_SCHEMA=? AND TABLE_NAME=?")) {
            s.setString(1,settings.database());s.setString(2,identity.table());try(ResultSet r=s.executeQuery()) {
                if(!r.next() || r.getBigDecimal(1)==null) throw new IllegalArgumentException("source_highwater_unknown");
                BigInteger n=r.getBigDecimal(1).toBigIntegerExact();if(n.signum()<1 || n.compareTo(BigInteger.valueOf(identity.maximum()))>0) throw new IllegalArgumentException("source_highwater_exhausted");
                values.put(identity.table(),n.toString());
            }
        }
        return values;
    }
    void finish() throws Exception {
        if(!next.equals(highwater())) throw new IllegalArgumentException("source_highwater_changed");
        try(Connection current=settings.connect()) {if(!server.equals(guard(current,settings))) throw new IllegalArgumentException("source_window_changed");}
        connection.commit();committed=true;
    }
    @Override public void close() throws SQLException {
        try {if(!committed) connection.rollback();} finally {connection.close();}
    }
}
