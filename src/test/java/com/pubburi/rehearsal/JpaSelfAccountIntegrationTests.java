package com.pubburi.rehearsal;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import com.pubburi.pub.model.service.PasswordHasher;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties="server.port=0")
@Import(JpaSelfAccountIntegrationTests.Probes.class)
@EnabledIfEnvironmentVariable(named="PUBBURI_JPA_INTEGRATION", matches="1")
class JpaSelfAccountIntegrationTests extends JpaAccountTestSupport {
    static final String PROFILE="/api/users/me/profile", PASSWORD="/api/users/me/password";
    @Autowired Gate gate;
    @Autowired LoginGateHasher hasher;
    Map<String,String> passwordInput(String current,String next) { return Map.of("currentPassword",current,"newPassword",next); }

    @Test void ownNameAndLiteralPasswordPreserveAllOtherCurrentFieldsAndPrivateChildren() throws Exception {
        var sql=control(); var admin=loggedIn("ADMIN"); String id=join(), other=join(); int product=create(admin,ownName());
        try {
            var owner=login(id,password); var second=login(id,password); var stranger=login(other,password);
            assertEquals(200,request(admin,"PUT","/api/admin/users/"+id,Map.of("stamps",63)).status()); children(sql,id,product);
            var before=accountState(sql,id); var otherBefore=accountState(sql,other); var source=sourceIdentity(sql); var productBefore=state(sql,product);
            assertEquals(200,request(owner,"PUT",PROFILE,Map.of("name"," literal name ")).status());
            assertEquals(" literal name ",request(second,"GET","/api/auth/me",null).body().path("data").path("name").textValue());
            var expected=new HashMap<>((Map<String,Object>)((List<?>)before.get("user")).get(0)); expected.put("name"," literal name ");
            assertEquals(expected,sql.queryForMap("SELECT * FROM pubburi.t_user WHERE id=?",id));
            assertEquals(403,request(owner,"PUT",PASSWORD,passwordInput("wrong-current", "new pass")).status());
            assertEquals(expected,sql.queryForMap("SELECT * FROM pubburi.t_user WHERE id=?",id));
            assertEquals(200,request(owner,"GET","/api/auth/me",null).status()); assertEquals(200,request(second,"GET","/api/auth/me",null).status());
            String next=" ab "; var changed=request(owner,"PUT",PASSWORD,passwordInput(password,next)); assertEquals(200,changed.status()); assertTrue(changed.body().path("data").booleanValue());
            assertEquals(Set.of("data","message","error"),keys(changed.body())); assertEquals(200,request(owner,"GET","/api/auth/me",null).status());
            assertEquals(401,request(second,"GET","/api/auth/me",null).status()); assertEquals(401,request(client(),"POST","/api/auth/login",Map.of("id",id,"pass",password)).status());
            assertEquals(401,request(client(),"POST","/api/auth/login",Map.of("id",id,"pass","ab")).status());
            var fresh=login(id,next); publicUser(profile(fresh,id).body().path("data").path("user"));
            var after=accountState(sql,id); var changedRow=new HashMap<>((Map<String,Object>)((List<?>)after.get("user")).get(0));
            assertNotEquals(expected.get("auth_epoch"),changedRow.get("auth_epoch")); assertNotEquals(expected.get("password_hash"),changedRow.get("password_hash"));
            changedRow.put("auth_epoch",expected.get("auth_epoch")); changedRow.put("password_hash",expected.get("password_hash")); assertEquals(expected,changedRow);
            after.put("user",before.get("user")); assertEquals(before,after); assertEquals(otherBefore,accountState(sql,other)); assertEquals(source,sourceIdentity(sql)); assertEquals(productBefore,state(sql,product));
            assertEquals(200,request(stranger,"GET","/api/auth/me",null).status());
            assertEquals(200,request(admin,"PUT","/api/admin/users/"+id,Map.of("role","ADMIN")).status());
            assertEquals(200,request(fresh,"PUT",PROFILE,Map.of("name","a".repeat(100))).status());
            assertEquals("ADMIN",request(fresh,"GET","/api/auth/me",null).body().path("data").path("role").textValue());
            for(String raw:List.of("z".repeat(73),"가".repeat(25),"😀".repeat(19))) {
                var unchanged=accountState(sql,id); assertEquals(400,request(fresh,"PUT",PASSWORD,passwordInput(next,raw)).status()); assertEquals(unchanged,accountState(sql,id));
            }
            for(String raw:List.of("z".repeat(72),"가".repeat(24),"😀".repeat(18))) {
                assertEquals(200,request(fresh,"PUT",PASSWORD,passwordInput(next,raw)).status()); fresh=login(id,raw); next=raw;
            }
        } finally { clean(sql,id,product,List.of()); clean(sql,other,null,List.of()); }
    }

    @Test void strictBodiesAndQuerySelectorsFailWithoutMutatingCredentialsOrSession() throws Exception {
        var sql=control(); String id=join();
        try {
            var owner=login(id,password); var before=accountState(sql,id);
            assertEquals(401,request(client(),"PUT",PROFILE,Map.of("name","new")).status());
            assertEquals(401,request(client(),"PUT",PASSWORD,passwordInput(password,"new pass")).status());
            for(String body:List.of("{}","null","[]","{\"name\":null}","{\"name\":1}","{\"name\":true}","{\"name\":\" \"}",
                    "{\"name\":\""+"a".repeat(101)+"\"}","{\"name\":\"valid\",\"role\":\"ADMIN\"}","{\"name\":\"valid\",\"stamps\":100}","{\"name\":\"valid\",\"id\":\"other\"}")) {
                assertEquals(400,request(owner,"PUT",PROFILE,body).status()); assertEquals(before,accountState(sql,id));
            }
            for(String body:List.of("{}","{\"currentPassword\":\"old pass\"}","{\"currentPassword\":1,\"newPassword\":\"new pass\"}",
                    "{\"currentPassword\":\"old pass\",\"newPassword\":true}","{\"currentPassword\":\"old pass\",\"newPassword\":\"abc\"}",
                    "{\"currentPassword\":\"old pass\",\"newPassword\":\"    \"}","{\"currentPassword\":\"old pass\",\"newPassword\":\""+"a".repeat(101)+"\"}",
                    "{\"currentPassword\":\"old pass\",\"newPassword\":\"new pass\",\"authEpoch\":\"forged\"}")) {
                assertEquals(400,request(owner,"PUT",PASSWORD,body).status()); assertEquals(before,accountState(sql,id));
            }
            assertEquals(400,request(owner,"PUT",PROFILE+"?id=other",Map.of("name","new")).status());
            assertEquals(400,request(owner,"PUT",PASSWORD+"?userId=other",passwordInput(password,"new pass")).status()); assertEquals(before,accountState(sql,id));
            assertEquals(200,request(owner,"GET","/api/auth/me",null).status());
            var docs=request(owner,"GET","/v3/api-docs",null); assertEquals(200,docs.status());
            for(String path:List.of(PROFILE,PASSWORD)) assertTrue(docs.body().path("paths").path(path).has("put"));
            var schemas=docs.body().path("components").path("schemas");
            assertFalse(schemas.path("SelfProfileRequest").path("additionalProperties").booleanValue());
            assertEquals(Set.of("name"),keys(schemas.path("SelfProfileRequest").path("properties")));
            assertEquals(Set.of("currentPassword","newPassword"),keys(schemas.path("SelfPasswordRequest").path("properties")));
        } finally { clean(sql,id,null,List.of()); }
    }

    @Test void queuedOldEpochCannotRenameOrResetACredentialRotatedOrRejoinedAccount() throws Exception {
        var sql=control(); var admin=loggedIn("ADMIN"); String id=join(); var executor=Executors.newSingleThreadExecutor();
        try {
            for(String path:List.of(PROFILE,PASSWORD)) {
                var owner=login(id,password);
                try(var pause=gate.arm("lock")) {
                    var writing=executor.submit(() -> request(owner,"PUT",path,path.equals(PROFILE)?Map.of("name","stale"):passwordInput(password,"stale pass")));
                    assertTrue(pause.entered.await(4,TimeUnit.SECONDS));
                    assertEquals(200,request(admin,"PUT","/api/admin/users/"+id,Map.of("pass","rotated pass")).status()); var before=accountState(sql,id);
                    pause.release.countDown(); assertEquals(401,writing.get(6,TimeUnit.SECONDS).status()); assertEquals(before,accountState(sql,id));
                }
                var reset=login(id,"rotated pass");
                try(var pause=gate.arm("lock")) {
                    var writing=executor.submit(() -> request(reset,"PUT",path,path.equals(PROFILE)?Map.of("name","rejoined stale"):passwordInput("rotated pass","stale pass")));
                    assertTrue(pause.entered.await(4,TimeUnit.SECONDS));
                    assertEquals(200,request(admin,"DELETE","/api/admin/users/"+id,null).status()); assertEquals(201,request(client(),"POST","/api/users",registration(id)).status()); var before=accountState(sql,id);
                    pause.release.countDown(); assertEquals(401,writing.get(6,TimeUnit.SECONDS).status()); assertEquals(before,accountState(sql,id));
                }
            }
        } finally { executor.shutdownNow(); assertTrue(executor.awaitTermination(3,TimeUnit.SECONDS)); clean(sql,id,null,List.of()); }
    }

    @Test void competingSelfPasswordsSerializeAndNameUpdatesDoNotRevertCurrentStamps() throws Exception {
        var sql=control(); var admin=loggedIn("ADMIN"); String id=join(); var executor=Executors.newFixedThreadPool(2);
        try {
            var one=login(id,password); var two=login(id,password);
            try(var pause=gate.arm("update")) {
                var first=executor.submit(() -> request(one,"PUT",PASSWORD,passwordInput(password,"first pass"))); assertTrue(pause.entered.await(4,TimeUnit.SECONDS));
                var observed=gate.observeLock(); var second=executor.submit(() -> request(two,"PUT",PASSWORD,passwordInput(password,"second pass"))); assertTrue(observed.await(4,TimeUnit.SECONDS));
                assertThrows(TimeoutException.class,() -> second.get(200,TimeUnit.MILLISECONDS)); pause.release.countDown(); assertEquals(200,first.get(6,TimeUnit.SECONDS).status()); assertEquals(401,second.get(6,TimeUnit.SECONDS).status());
            }
            assertEquals(200,request(one,"GET","/api/auth/me",null).status()); assertEquals(401,request(two,"GET","/api/auth/me",null).status());
            login(id,"first pass"); assertEquals(401,request(client(),"POST","/api/auth/login",Map.of("id",id,"pass","second pass")).status());
            try(var pause=gate.arm("update")) {
                var first=executor.submit(() -> request(one,"PUT",PROFILE,Map.of("name","serialized name"))); assertTrue(pause.entered.await(4,TimeUnit.SECONDS));
                var observed=gate.observeLock(); var second=executor.submit(() -> request(admin,"PUT","/api/admin/users/"+id,Map.of("stamps",63))); assertTrue(observed.await(4,TimeUnit.SECONDS));
                assertThrows(TimeoutException.class,() -> second.get(200,TimeUnit.MILLISECONDS)); pause.release.countDown(); assertEquals(200,first.get(6,TimeUnit.SECONDS).status()); assertEquals(200,second.get(6,TimeUnit.SECONDS).status());
            }
            var user=profile(one,id).body().path("data").path("user"); assertEquals("serialized name",user.path("name").textValue()); assertEquals(63,user.path("stamps").intValue());
        } finally { executor.shutdownNow(); assertTrue(executor.awaitTermination(3,TimeUnit.SECONDS)); clean(sql,id,null,List.of()); }
    }

    @Test void loginValidatedBeforeCredentialRotationCannotAuthorizeTheOldEpochAfterward() throws Exception {
        var sql=control(); String id=join(); var executor=Executors.newSingleThreadExecutor(); var pause=new Pause("hash");
        try {
            var owner=login(id,password); var late=client();
            assertTrue(hasher.armed.compareAndSet(null,pause));
            var pending=executor.submit(() -> request(late,"POST","/api/auth/login",Map.of("id",id,"pass",password))); assertTrue(pause.entered.await(4,TimeUnit.SECONDS));
            assertEquals(200,request(owner,"PUT",PASSWORD,passwordInput(password,"rotated pass")).status()); pause.release.countDown();
            assertEquals(200,pending.get(6,TimeUnit.SECONDS).status()); // Existing login snapshot may complete; protected access rechecks the epoch.
            assertEquals(401,request(late,"GET","/api/auth/me",null).status()); assertEquals(200,request(owner,"GET","/api/auth/me",null).status()); login(id,"rotated pass");
        } finally { pause.close(); hasher.armed.set(null); executor.shutdownNow(); assertTrue(executor.awaitTermination(3,TimeUnit.SECONDS)); clean(sql,id,null,List.of()); }
    }

    @Test void shortExistingLegacyAndUpgradedPasswordsCanChangeWithoutANewPasswordPolicyOnCurrentInput() throws Exception {
        var sql=control(); String id=join();
        try {
            var owner=login(id,password);
            for(boolean upgrade:List.of(false,true)) {
                String shortPass=upgrade?"y":"x";
                var digest=java.security.MessageDigest.getInstance("SHA-256").digest(shortPass.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                sql.update("UPDATE pubburi.t_user SET password_hash=? WHERE id=?",HexFormat.of().formatHex(digest),id);
                if(upgrade) { owner=login(id,shortPass); assertTrue(sql.queryForObject("SELECT password_hash FROM pubburi.t_user WHERE id=?",String.class,id).startsWith("$2")); }
                assertEquals(200,request(owner,"PUT",PASSWORD,passwordInput(shortPass,"valid new pass")).status());
                owner=login(id,"valid new pass");
            }
        } finally { clean(sql,id,null,List.of()); }
    }

    static final class LoginGateHasher extends PasswordHasher {
        final AtomicReference<Pause> armed=new AtomicReference<>();
        @Override public boolean matches(String raw,String hashed) {
            boolean result=super.matches(raw,hashed); var pause=armed.getAndSet(null); if(pause!=null)pause.await(); return result;
        }
    }

    static final class Pause implements AutoCloseable {
        final String kind; final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        Pause(String kind) { this.kind=kind; }
        void await() { entered.countDown(); try { if(!release.await(6,TimeUnit.SECONDS))throw new IllegalStateException("owned self gate deadline"); } catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); } }
        @Override public void close() { release.countDown(); }
    }
    static final class Gate implements StatementInspector {
        final AtomicReference<Pause> armed=new AtomicReference<>(); final AtomicReference<CountDownLatch> observed=new AtomicReference<>();
        Pause arm(String kind) { var pause=new Pause(kind); assertTrue(armed.compareAndSet(null,pause)); return pause; }
        CountDownLatch observeLock() { var latch=new CountDownLatch(1); assertTrue(observed.compareAndSet(null,latch)); return latch; }
        @Override public String inspect(String sql) {
            String lower=sql.toLowerCase(Locale.ROOT); boolean lock=lower.startsWith("select id from pubburi.t_user ")&&lower.contains("for update");
            if(lock) { var latch=observed.getAndSet(null); if(latch!=null)latch.countDown(); }
            var pause=armed.get(); if(pause!=null&&(pause.kind.equals("lock")?lock:lower.startsWith("update pubburi.t_user "))&&armed.compareAndSet(pause,null)) pause.await();
            return sql;
        }
    }
    @TestConfiguration(proxyBeanMethods=false) static class Probes {
        @Bean Gate selfGate() { return new Gate(); }
        @Bean @Primary LoginGateHasher selfHasher() { return new LoginGateHasher(); }
        @Bean HibernatePropertiesCustomizer selfProbe(Gate gate) { return values -> values.put("hibernate.session_factory.statement_inspector",gate); }
    }
}
