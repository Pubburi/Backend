package com.pubburi.rehearsal;

import com.zaxxer.hikari.*;
import java.sql.*;
import java.util.*;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods=false)
class RehearsalDataSource {
    @Bean(destroyMethod="close")
    HikariDataSource dataSource(Environment environment) {
        RehearsalPolicies.validate(environment);
        var values=new HashMap<String,String>();for(String key:List.of("PUBBURI_JPA_REHEARSAL","PUBBURI_PG_URL","PUBBURI_PG_DB_NAME","PUBBURI_PG_USER","PUBBURI_PG_PASSWORD","PUBBURI_PG_RUN_TOKEN","PUBBURI_PG_CA","PUBBURI_JPA_SOURCE_ID","server.address")) {
            String value=environment.getProperty(key);if(value!=null)values.put(key,value);
        }
        var settings=RehearsalSettings.from(values);var config=new HikariConfig();
        config.setPoolName("pubburi-jpa-rehearsal");config.setJdbcUrl(settings.url());config.setUsername(settings.user());config.setPassword(settings.password());
        config.setDataSourceProperties(settings.properties());config.setMaximumPoolSize(4);config.setMinimumIdle(0);config.setConnectionTimeout(5000);config.setInitializationFailTimeout(5000);
        HikariDataSource source=new HikariDataSource(config);
        try {
            try(Connection connection=source.getConnection()) {guard(connection,settings);}
            // Explicit validation only: no automatic migration, clean/baseline/repair or source adoption.
            Flyway.configure().dataSource(source).schemas("pubburi").defaultSchema("pubburi").locations("classpath:db/postgresql")
                .cleanDisabled(true).baselineOnMigrate(false).connectRetries(0).loggers("slf4j").load().validate();
            return source;
        } catch(Exception error){source.close();throw new IllegalStateException("jpa_rehearsal_fixture_or_schema_rejected");}
    }
    static void guard(Connection c,RehearsalSettings s)throws SQLException {
        try(var statement=c.prepareStatement("""
            SELECT current_database(),current_setting('server_version_num'),current_setting('server_encoding'),
              (SELECT ssl FROM pg_stat_ssl WHERE pid=pg_backend_pid()),
              (SELECT rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls FROM pg_roles WHERE rolname=current_user),
              EXISTS(SELECT 1 FROM public.pubburi_pg_migration_guard WHERE token=?),
              EXISTS(SELECT 1 FROM pubburi.migration_source_receipt r JOIN pubburi.t_product_search_relay_target t
                ON t.singleton_id=r.singleton_id AND t.source_id=r.source_id WHERE r.singleton_id=1 AND r.source_id=CAST(? AS uuid)),
              has_database_privilege(current_database(),'CREATE') OR has_schema_privilege('pubburi','CREATE'),
              EXISTS(SELECT 1 FROM pg_auth_members WHERE member=(SELECT oid FROM pg_roles WHERE rolname=current_user)),
              EXISTS(SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='pubburi' AND c.relowner=(SELECT oid FROM pg_roles WHERE rolname=current_user))
                OR EXISTS(SELECT 1 FROM pg_namespace WHERE nspname='pubburi' AND nspowner=(SELECT oid FROM pg_roles WHERE rolname=current_user)),
              has_table_privilege('pubburi.t_user','TRIGGER,TRUNCATE') OR has_table_privilege('pubburi.t_market','TRIGGER,TRUNCATE')
                OR has_table_privilege('pubburi.t_product','INSERT,UPDATE,DELETE,TRIGGER,TRUNCATE')
                OR has_table_privilege('pubburi.t_product_feature','INSERT,UPDATE,DELETE,TRIGGER,TRUNCATE')
                OR has_table_privilege('pubburi.t_product_feature_tag','INSERT,UPDATE,DELETE,TRIGGER,TRUNCATE')
                OR has_sequence_privilege('pubburi.t_product_id_seq','USAGE,SELECT,UPDATE')
            """)) {
            statement.setString(1,s.token());statement.setString(2,s.sourceId());try(var r=statement.executeQuery()) {
                if(!r.next() || !s.database().equals(r.getString(1)) || !"180006".equals(r.getString(2)) || !"UTF8".equals(r.getString(3)) || !r.getBoolean(4) || r.getBoolean(5) || !r.getBoolean(6) || !r.getBoolean(7) || r.getBoolean(8) || r.getBoolean(9) || r.getBoolean(10) || r.getBoolean(11))throw new IllegalArgumentException("jpa_rehearsal_guard_rejected");
            }
        }
    }
}
