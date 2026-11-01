package com.pubburi.migration;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PostgresSchemaToolTests {
    @TempDir Path temporary;

    Map<String, String> settings() throws Exception {
        Path ca = temporary.resolve("authored-ca.pem"); Files.writeString(ca, "Config fixture only; not a real CA");
        return new HashMap<>(Map.of("PUBBURI_PG_URL", "jdbc:postgresql://127.0.0.1:5432/schema_ci",
                "PUBBURI_PG_DB_NAME", "schema_ci", "PUBBURI_PG_USER", "fixture",
                "PUBBURI_PG_PASSWORD", "not-a-real-credential", "PUBBURI_PG_RUN_TOKEN", "authored-fixture-token",
                "PUBBURI_PG_CA", ca.toString()));
    }

    @Test void onlyAnExplicitLoopbackFixtureAndSeparateCredentialsAreAccepted() throws Exception {
        Map<String, String> env = settings();
        assertEquals("schema_ci", PostgresSchemaTool.Settings.from(env).database());
        for (String url : new String[]{"jdbc:mysql://127.0.0.1:5432/schema_ci", "jdbc:postgresql://example.com:5432/schema_ci",
                "jdbc:postgresql://localhost:5432/schema_ci", "jdbc:postgresql://u:p@127.0.0.1:5432/schema_ci",
                "jdbc:postgresql://127.0.0.1/schema_ci", "jdbc:postgresql://127.0.0.1:5432/other_ci",
                "jdbc:postgresql://127.0.0.1:5432/schema_ci?sslmode=disable", "jdbc:postgresql://127.0.0.1:5432/schema_ci#x"}) {
            Map<String, String> bad = new HashMap<>(env); bad.put("PUBBURI_PG_URL", url);
            assertThrows(IllegalArgumentException.class, () -> PostgresSchemaTool.Settings.from(bad));
        }
        for (String key : new String[]{"PUBBURI_PG_PASSWORD", "PUBBURI_PG_RUN_TOKEN", "PUBBURI_PG_CA"}) {
            Map<String, String> bad = new HashMap<>(env); bad.remove(key);
            assertThrows(IllegalArgumentException.class, () -> PostgresSchemaTool.Settings.from(bad));
        }
        env.put("PUBBURI_PG_DB_NAME", "production"); env.put("PUBBURI_PG_URL", "jdbc:postgresql://127.0.0.1:5432/production");
        assertThrows(IllegalArgumentException.class, () -> PostgresSchemaTool.Settings.from(env));
    }

    @Test void everyConnectionRequiresVerifiedTlsScramAndBoundedTimeoutsWithoutCredentialPrinting() throws Exception {
        var settings = PostgresSchemaTool.Settings.from(settings());
        assertEquals("verify-full", settings.properties().getProperty("sslmode"));
        assertEquals("scram-sha-256", settings.properties().getProperty("requireAuth"));
        assertEquals("3", settings.properties().getProperty("connectTimeout"));
        assertEquals("5", settings.properties().getProperty("socketTimeout"));
        assertFalse(settings.toString().contains(settings.password()));
        assertFalse(settings.toString().contains(settings.user()));
    }

    @Test void destructiveCommandsAndImplicitMutationAreRejectedBeforeConnection() throws Exception {
        Map<String, String> env = settings();
        for (String command : new String[]{"clean", "repair", "baseline", "drop", ""})
            assertThrows(IllegalArgumentException.class, () -> PostgresSchemaTool.run(command, env));
        assertThrows(IllegalArgumentException.class, () -> PostgresSchemaTool.run("migrate", env));
    }

    @Test void anActualNonTlsPeerIsRejectedBeforeCredentialsAreSent() throws Exception {
        try(var listener=new java.net.ServerSocket(0,1,java.net.InetAddress.getByName("127.0.0.1"))) {
            var observed=new java.util.concurrent.CompletableFuture<Boolean>();
            Thread peer=new Thread(()->{
                try(var socket=listener.accept()) {
                    socket.setSoTimeout(3000);
                    byte[] request=socket.getInputStream().readNBytes(8);
                    boolean sslRequest=java.util.Arrays.equals(request,new byte[]{0,0,0,8,4,(byte)210,22,47});
                    socket.getOutputStream().write('N');socket.getOutputStream().flush();
                    observed.complete(sslRequest && socket.getInputStream().read()==-1);
                } catch(Exception error) { observed.completeExceptionally(error); }
            },"owned-postgresql-non-tls-peer");
            peer.setDaemon(true);peer.start();
            Map<String,String> env=settings();
            env.put("PUBBURI_PG_URL","jdbc:postgresql://127.0.0.1:"+listener.getLocalPort()+"/schema_ci");
            assertThrows(java.sql.SQLException.class,()->PostgresSchemaTool.Settings.from(env).connect());
            assertTrue(observed.get(4,java.util.concurrent.TimeUnit.SECONDS));
            peer.join(4000);assertFalse(peer.isAlive());
        }
    }
}
