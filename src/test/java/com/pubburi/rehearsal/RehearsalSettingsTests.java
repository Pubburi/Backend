package com.pubburi.rehearsal;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RehearsalSettingsTests {
    @TempDir Path temp;
    Map<String,String> env()throws Exception {
        Path ca=temp.resolve("ca.pem");Files.writeString(ca,"authored config fixture");var env=new HashMap<String,String>();
        env.put("PUBBURI_JPA_REHEARSAL","1");env.put("PUBBURI_PG_URL","jdbc:postgresql://127.0.0.1:54321/jpa_ci");env.put("PUBBURI_PG_DB_NAME","jpa_ci");
        env.put("PUBBURI_PG_USER","fixture");env.put("PUBBURI_PG_PASSWORD","authored-password");env.put("PUBBURI_PG_RUN_TOKEN","authored-run-token");env.put("PUBBURI_PG_CA",ca.toString());env.put("PUBBURI_JPA_SOURCE_ID","00000000-0000-4000-8000-000000000001");return env;
    }
    @Test void implicitOrRemoteOrUserDatabaseAndUnverifiedUrlOptionsAreRejected()throws Exception {
        var env=env();assertEquals("jpa_ci",RehearsalSettings.from(env).database());
        for(String key:List.of("PUBBURI_JPA_REHEARSAL","PUBBURI_PG_PASSWORD","PUBBURI_PG_RUN_TOKEN","PUBBURI_PG_CA","PUBBURI_JPA_SOURCE_ID")){var bad=new HashMap<>(env);bad.remove(key);assertThrows(IllegalArgumentException.class,()->RehearsalSettings.from(bad));}
        for(String url:List.of("jdbc:mysql://127.0.0.1:54321/jpa_ci","jdbc:postgresql://localhost:54321/jpa_ci","jdbc:postgresql://127.0.0.1:54321/jpa_ci?sslmode=disable","jdbc:postgresql://u:p@127.0.0.1:54321/jpa_ci","jdbc:postgresql://127.0.0.1:54321/other_ci")){var bad=new HashMap<>(env);bad.put("PUBBURI_PG_URL",url);assertThrows(IllegalArgumentException.class,()->RehearsalSettings.from(bad));}
        env.put("server.address","0.0.0.0");assertThrows(IllegalArgumentException.class,()->RehearsalSettings.from(env));
    }
    @Test void tlsAndScramAreRequiredAndCredentialsAreNotPrinted()throws Exception {
        var settings=RehearsalSettings.from(env());assertEquals("verify-full",settings.properties().getProperty("sslmode"));assertEquals("scram-sha-256",settings.properties().getProperty("requireAuth"));
        assertEquals("5",settings.properties().getProperty("socketTimeout"));assertFalse(settings.toString().contains(settings.password()));assertFalse(settings.toString().contains(settings.user()));
    }
    @Test void automaticSchemaMutationAndOsivOverridesAreRejected() {
        var env=new org.springframework.mock.env.MockEnvironment().withProperty("spring.jpa.hibernate.ddl-auto","validate").withProperty("spring.jpa.open-in-view","false").withProperty("spring.flyway.enabled","false").withProperty("spring.sql.init.mode","never");
        RehearsalPolicies.validate(env);
        for(String key:List.of("spring.jpa.open-in-view","spring.jpa.generate-ddl","spring.flyway.enabled")) {
            env.setProperty(key,"true");assertThrows(IllegalArgumentException.class,()->RehearsalPolicies.validate(env));env.setProperty(key,"false");
        }
        env.setProperty("spring.jpa.hibernate.ddl-auto","create");assertThrows(IllegalArgumentException.class,()->RehearsalPolicies.validate(env));
        var properties=new HashMap<String,Object>();properties.put("hibernate.hbm2ddl.auto","create");new RehearsalPolicies().validateOnlyHibernate().customize(properties);assertEquals("validate",properties.get("hibernate.hbm2ddl.auto"));
        properties.put("jakarta.persistence.schema-generation.database.action","create");assertThrows(IllegalArgumentException.class,()->new RehearsalPolicies().validateOnlyHibernate().customize(properties));
    }
}
