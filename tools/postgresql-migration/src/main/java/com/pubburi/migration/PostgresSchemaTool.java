package com.pubburi.migration;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.Properties;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;

/** Rehearsal-only installer: no clean, repair, baseline or application API profile. */
public final class PostgresSchemaTool {
    public static final String SCHEMA = "pubburi";
    public static final String LOCATION = "classpath:db/postgresql";

    private PostgresSchemaTool() { }

    public record Settings(String url, String database, String user, String password, String token, Path ca) {
        public static Settings from(Map<String, String> env) {
            try {
                String url = env.get("PUBBURI_PG_URL");
                if (url == null || !url.startsWith("jdbc:postgresql://")) throw new IllegalArgumentException();
                URI uri = URI.create(url.substring(5));
                String database = env.get("PUBBURI_PG_DB_NAME");
                if (!("127.0.0.1".equals(uri.getHost()) || "[::1]".equals(uri.getHost()) || "::1".equals(uri.getHost()))
                        || uri.getPort() < 1 || uri.getPort() > 65535 || uri.getUserInfo() != null
                        || uri.getRawQuery() != null || uri.getRawFragment() != null
                        || database == null || !database.matches("[a-z][a-z0-9_]*_ci")
                        || !("/" + database).equals(uri.getRawPath())) throw new IllegalArgumentException();
                String user = required(env, "PUBBURI_PG_USER");
                String password = required(env, "PUBBURI_PG_PASSWORD");
                String token = required(env, "PUBBURI_PG_RUN_TOKEN");
                if (!token.matches("[A-Za-z0-9_-]{12,100}")) throw new IllegalArgumentException();
                Path ca = Path.of(required(env, "PUBBURI_PG_CA"));
                if (!Files.isRegularFile(ca) || !Files.isReadable(ca)) throw new IllegalArgumentException();
                return new Settings(url, database, user, password, token, ca);
            } catch (RuntimeException error) {
                throw new IllegalArgumentException("postgresql_explicit_local_fixture_required");
            }
        }

        Properties properties() {
            Properties result = new Properties();
            result.setProperty("user", user); result.setProperty("password", password);
            result.setProperty("sslmode", "verify-full"); result.setProperty("sslrootcert", ca.toAbsolutePath().toString());
            result.setProperty("gssEncMode", "disable");
            result.setProperty("requireAuth", "scram-sha-256");
            result.setProperty("connectTimeout", "3"); result.setProperty("socketTimeout", "5");
            result.setProperty("options", "-c timezone=UTC -c statement_timeout=5000 -c lock_timeout=2000");
            return result;
        }

        Connection connect() throws SQLException { return DriverManager.getConnection(url, properties()); }

        // Do not expose the record's generated credential-bearing representation.
        @Override public String toString() { return "PostgresMigrationSettings[redacted]"; }
    }

    private static String required(Map<String, String> env, String name) {
        String value = env.get(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException();
        return value;
    }

    public static void guard(Settings settings) throws SQLException {
        try (Connection connection = settings.connect()) {
            connection.setReadOnly(true);
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT current_database() AS db, current_setting('server_version_num') AS version,
                           current_setting('server_encoding') AS encoding,
                           EXISTS (SELECT 1 FROM public.pubburi_pg_migration_guard WHERE token=?) AS marker,
                           (SELECT ssl FROM pg_stat_ssl WHERE pid=pg_backend_pid()) AS tls,
                           (SELECT rolsuper FROM pg_roles WHERE rolname=current_user) AS superuser
                    """)) {
                statement.setString(1, settings.token());
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next() || !settings.database().equals(result.getString("db"))
                            || !"180006".equals(result.getString("version")) || !"UTF8".equals(result.getString("encoding"))
                            || !result.getBoolean("marker") || !result.getBoolean("tls") || result.getBoolean("superuser"))
                        throw new IllegalArgumentException("postgresql_fixture_guard_rejected");
                }
            }
        }
    }

    static FluentConfiguration configuration(Settings settings, String location) {
        // Driver properties are passed separately; credentials never enter JDBC URL or arguments.
        return Flyway.configure().dataSource(new GuardedDataSource(settings))
                .locations(location).schemas(SCHEMA).defaultSchema(SCHEMA)
                .createSchemas(true).cleanDisabled(true).baselineOnMigrate(false).outOfOrder(false)
                .validateMigrationNaming(true).validateOnMigrate(true).failOnMissingLocations(true)
                .connectRetries(0).lockRetryCount(0).loggers("slf4j");
    }

    public static int run(String command, Map<String, String> env) throws SQLException, java.io.IOException {
        if (!java.util.Set.of("migrate", "validate", "reserve-identities").contains(command)) throw new IllegalArgumentException("postgresql_command_rejected");
        if (!"validate".equals(command) && !"1".equals(env.get("PUBBURI_PG_MUTATING")))
            throw new IllegalArgumentException("postgresql_explicit_mutation_required");
        Settings settings = Settings.from(env);
        guard(settings);
        Flyway flyway = configuration(settings, LOCATION).load();
        if ("migrate".equals(command)) {
            int migrated=flyway.migrate().migrationsExecuted;
            SchemaIndexContract.verify(settings); return migrated;
        }
        flyway.validate();
        SchemaIndexContract.verify(settings);
        if ("reserve-identities".equals(command))
            return IdentityReservations.apply(settings, IdentityReservations.read(Path.of(required(env, "PUBBURI_PG_IDENTITY_FILE"))));
        return 0;
    }

    public static int mainResult(String[] args, Map<String, String> env) {
        try {
            if (args.length != 1) throw new IllegalArgumentException("postgresql_command_rejected");
            int count = run(args[0], env);
            System.out.println("{\"status\":\"passed\",\"command\":\"" + args[0] + "\",\"changes_applied\":" + count + "}");
            return 0;
        } catch (Exception error) {
            System.err.println("{\"status\":\"failed\",\"error_code\":\"postgresql_schema_operation_rejected\"}");
            return 2;
        }
    }

    public static void main(String[] args) { System.exit(mainResult(args, System.getenv())); }
}
