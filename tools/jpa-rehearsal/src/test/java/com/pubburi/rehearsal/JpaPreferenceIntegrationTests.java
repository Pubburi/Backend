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
import org.springframework.dao.DataAccessException;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "server.port=0")
@Import(JpaPreferenceIntegrationTests.Probes.class)
@EnabledIfEnvironmentVariable(named = "PUBBURI_JPA_INTEGRATION", matches = "1")
class JpaPreferenceIntegrationTests extends JpaAccountTestSupport {
    static final String PATH = "/api/users/me/preferences";
    @Autowired Gate gate;
    static Map<String,Object> input(String tag, int price) { return Map.of("surveyStatus","COMPLETED", "softPreferences",Map.of("tasteTags",List.of(tag),"aromaTags",List.of(),"targetAbvPercent",15.25), "hardConstraints",Map.of("minPriceKrw",price,"maxAbvPercent",10)); }
    com.fasterxml.jackson.databind.JsonNode read(java.net.http.HttpClient client) throws Exception { var reply = request(client,"GET",PATH,null); assertEquals(200,reply.status()); return reply.body().path("data"); }
    void unknown(com.fasterxml.jackson.databind.JsonNode value, String status) {
        assertEquals(Set.of("surveyStatus","softPreferences","hardConstraints"),keys(value)); assertEquals(status,value.path("surveyStatus").textValue());
        assertEquals(Set.of("categories","tasteTags","aromaTags","targetAbvPercent"),keys(value.path("softPreferences")));
        assertEquals(Set.of("minPriceKrw","maxPriceKrw","minAbvPercent","maxAbvPercent"),keys(value.path("hardConstraints")));
        value.path("softPreferences").forEach(v -> assertTrue(v.isNull())); value.path("hardConstraints").forEach(v -> assertTrue(v.isNull()));
    }

    @Test void privateWholeReplacementKeepsUnknownEmptyZeroAndSkipResetDistinct() throws Exception {
        var sql=control(); var admin=loggedIn("ADMIN"); String user=join(), other=join();
        try {
            var owner=login(user,password); var second=login(user,password); var stranger=login(other,password); var account=accountState(sql,user);
            unknown(read(owner),"NOT_STARTED"); assertEquals(401,request(client(),"GET",PATH,null).status()); assertEquals(401,request(client(),"PUT",PATH,input("dry",0)).status());
            var value=new HashMap<>(input(" DRY ",0)); value.put("userId",other); value.put("authEpoch",UUID.randomUUID().toString());
            assertEquals(200,request(owner,"PUT",PATH+"?userId="+other,value).status()); var saved=read(second); var soft=saved.path("softPreferences"); var hard=saved.path("hardConstraints");
            assertTrue(soft.path("categories").isNull()); assertEquals(List.of("dry"),json.convertValue(soft.path("tasteTags"),List.class)); assertEquals(0,soft.path("aromaTags").size());
            assertEquals(15.25,soft.path("targetAbvPercent").doubleValue()); assertEquals(0,hard.path("minPriceKrw").intValue()); assertEquals(10,hard.path("maxAbvPercent").intValue());
            unknown(read(stranger),"NOT_STARTED"); unknown(read(admin),"NOT_STARTED");
            assertEquals(200,request(owner,"PUT",PATH,input(" DRY ",0)).status()); assertEquals(saved,read(owner));
            for(String status:List.of("SKIPPED","NOT_STARTED","COMPLETED")) { assertEquals(200,request(owner,"PUT",PATH,Map.of("surveyStatus",status)).status()); unknown(read(owner),status); assertEquals(0,sql.queryForObject("SELECT count(*) FROM pubburi.t_user_preference_value WHERE user_id=?",Integer.class,user)); }
            var after=accountState(sql,user); after.put("preference",account.get("preference")); after.put("values",account.get("values")); assertEquals(account,after);
            assertEquals(405,request(owner,"POST",PATH,input("dry",0)).status());
            var profile=profile(owner,user).body().path("data"); assertEquals(Set.of("user","orders","grade"),keys(profile)); publicUser(profile.path("user"));
        } finally { clean(sql,user,null,List.of()); clean(sql,other,null,List.of()); }
    }

    @Test void invalidInputNeverRoundsCoercesOrPartiallyReplacesStoredValues() throws Exception {
        var sql=control(); String user=join();
        try {
            var owner=login(user,password); assertEquals(200,request(owner,"PUT",PATH,input("dry",0)).status()); var before=accountState(sql,user);
            for(String body:List.of("{}","{\"surveyStatus\":true}","{\"surveyStatus\":\"UNKNOWN\"}",
                "{\"surveyStatus\":\"SKIPPED\",\"softPreferences\":{\"tasteTags\":[]}}", "{\"surveyStatus\":\"NOT_STARTED\",\"hardConstraints\":{\"minPriceKrw\":0}}",
                "{\"surveyStatus\":\"COMPLETED\",\"softPreferences\":{\"tasteTags\":[\" DRY \",\"dry\"]}}", "{\"surveyStatus\":\"COMPLETED\",\"softPreferences\":{\"tasteTags\":[null]}}",
                "{\"surveyStatus\":\"COMPLETED\",\"softPreferences\":{\"tasteTags\":[true]}}", "{\"surveyStatus\":\"COMPLETED\",\"softPreferences\":{\"categories\":[\"unknown\"]}}",
                "{\"surveyStatus\":\"COMPLETED\",\"softPreferences\":{\"targetAbvPercent\":5.555}}", "{\"surveyStatus\":\"COMPLETED\",\"softPreferences\":{\"targetAbvPercent\":\"5\"}}",
                "{\"surveyStatus\":\"COMPLETED\",\"hardConstraints\":{\"minPriceKrw\":1.5}}", "{\"surveyStatus\":\"COMPLETED\",\"hardConstraints\":{\"minPriceKrw\":2147483648}}",
                "{\"surveyStatus\":\"COMPLETED\",\"hardConstraints\":{\"minPriceKrw\":-1}}", "{\"surveyStatus\":\"COMPLETED\",\"hardConstraints\":{\"minPriceKrw\":2,\"maxPriceKrw\":1}}",
                "{\"surveyStatus\":\"COMPLETED\",\"hardConstraints\":{\"minAbvPercent\":101}}", "{\"surveyStatus\":\"COMPLETED\",\"hardConstraints\":{\"minAbvPercent\":2,\"maxAbvPercent\":1}}")) {
                assertEquals(400,request(owner,"PUT",PATH,body).status(),body); assertEquals(before,accountState(sql,user));
            }
            assertEquals(400,request(owner,"PUT",PATH,Map.of("surveyStatus","COMPLETED","softPreferences",Map.of("tasteTags",Collections.nCopies(21,"dry")))).status()); assertEquals(before,accountState(sql,user));
            assertEquals(200,request(owner,"PUT",PATH,Map.of("surveyStatus","COMPLETED","softPreferences",Map.of("categories",List.of(" 증류주 ","탁주"),"tasteTags",List.of("Z","a","A,B")))).status());
            var sorted=read(owner).path("softPreferences"); assertEquals(List.of("a","a,b","z"),json.convertValue(sorted.path("tasteTags"),List.class)); assertEquals(List.of("증류주","탁주"),json.convertValue(sorted.path("categories"),List.class)); assertTrue(sorted.path("targetAbvPercent").isNull());
        } finally { clean(sql,user,null,List.of()); }
    }

    @Test void headerAndValuesUseTheSameRepeatableReadSnapshot() throws Exception {
        var sql=control(); String user=join(); var executor=Executors.newSingleThreadExecutor();
        try {
            var owner=login(user,password); var writer=login(user,password); assertEquals(200,request(owner,"PUT",PATH,input("old",1)).status()); var before=read(owner);
            try(var pause=gate.arm("values")) {
                var reading=executor.submit(() -> read(owner)); assertTrue(pause.entered.await(4,TimeUnit.SECONDS));
                assertEquals(200,request(writer,"PUT",PATH,input("new",2)).status()); pause.release.countDown(); assertEquals(before,reading.get(6,TimeUnit.SECONDS));
            }
            var next=read(owner); assertEquals("new",next.path("softPreferences").path("tasteTags").get(0).textValue()); assertEquals(2,next.path("hardConstraints").path("minPriceKrw").intValue());
        } finally { executor.shutdownNow(); assertTrue(executor.awaitTermination(3,TimeUnit.SECONDS)); clean(sql,user,null,List.of()); }
    }

    @Test void queuedOldEpochCannotWriteAfterPasswordRotationOrSameIdRejoin() throws Exception {
        var sql=control(); var admin=loggedIn("ADMIN"); String user=join(); var executor=Executors.newSingleThreadExecutor();
        try {
            var owner=login(user,password); assertEquals(200,request(owner,"PUT",PATH,input("old",1)).status()); var before=read(owner);
            try(var pause=gate.arm("userLock")) {
                var writing=executor.submit(() -> request(owner,"PUT",PATH,input("stale",2))); assertTrue(pause.entered.await(4,TimeUnit.SECONDS));
                assertEquals(200,request(admin,"PUT","/api/admin/users/"+user,Map.of("pass","Authored-preference-rotated")).status()); pause.release.countDown(); assertEquals(401,writing.get(6,TimeUnit.SECONDS).status());
            }
            var fresh=login(user,"Authored-preference-rotated"); assertEquals(before,read(fresh)); var dormant=login(user,"Authored-preference-rotated");
            try(var pause=gate.arm("userLock")) {
                var writing=executor.submit(() -> request(fresh,"PUT",PATH,input("recreated",3))); assertTrue(pause.entered.await(4,TimeUnit.SECONDS));
                assertEquals(200,request(admin,"DELETE","/api/admin/users/"+user,null).status()); assertEquals(201,request(client(),"POST","/api/users",registration(user)).status());
                pause.release.countDown(); assertEquals(401,writing.get(6,TimeUnit.SECONDS).status());
            }
            assertEquals(401,request(dormant,"GET",PATH,null).status()); unknown(read(login(user,password)),"NOT_STARTED");
            assertEquals(0,sql.queryForObject("SELECT count(*) FROM pubburi.t_user_preference WHERE user_id=?",Integer.class,user)); assertEquals(0,sql.queryForObject("SELECT count(*) FROM pubburi.t_user_preference_value WHERE user_id=?",Integer.class,user));
        } finally { executor.shutdownNow(); assertTrue(executor.awaitTermination(3,TimeUnit.SECONDS)); clean(sql,user,null,List.of()); }
    }

    @Test void concurrentWholeReplacementsSerializeWithoutMixedFieldsOrExtraPrivileges() throws Exception {
        var sql=control(); String user=join(); var executor=Executors.newFixedThreadPool(2);
        try {
            var one=login(user,password); var two=login(user,password); assertEquals(200,request(one,"PUT",PATH,input("initial",0)).status());
            try(var pause=gate.arm("delete")) {
                var first=executor.submit(() -> request(one,"PUT",PATH,input("first",1))); assertTrue(pause.entered.await(4,TimeUnit.SECONDS));
                var observed=gate.observeUserLock(); var second=executor.submit(() -> request(two,"PUT",PATH,input("second",2))); assertTrue(observed.await(4,TimeUnit.SECONDS));
                assertThrows(TimeoutException.class,() -> second.get(200,TimeUnit.MILLISECONDS)); pause.release.countDown(); assertEquals(200,first.get(6,TimeUnit.SECONDS).status()); assertEquals(200,second.get(6,TimeUnit.SECONDS).status());
            }
            var result=read(one); assertEquals("second",result.path("softPreferences").path("tasteTags").get(0).textValue()); assertEquals(2,result.path("hardConstraints").path("minPriceKrw").intValue()); assertEquals(1,sql.queryForObject("SELECT count(*) FROM pubburi.t_user_preference_value WHERE user_id=?",Integer.class,user));
            for(String statement:List.of("DELETE FROM pubburi.t_user_preference WHERE false","UPDATE pubburi.t_user_preference SET user_id=user_id WHERE false","UPDATE pubburi.t_user_preference_value SET value_text=value_text WHERE false","TRUNCATE pubburi.t_user_preference_value")) assertThrows(DataAccessException.class,() -> runtime.execute(statement));
        } finally { executor.shutdownNow(); assertTrue(executor.awaitTermination(3,TimeUnit.SECONDS)); clean(sql,user,null,List.of()); }
    }

    static final class Pause implements AutoCloseable {
        final String kind; final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        Pause(String kind) { this.kind=kind; } @Override public void close() { release.countDown(); }
    }
    static final class Gate implements StatementInspector {
        final AtomicReference<Pause> armed=new AtomicReference<>(); final AtomicReference<CountDownLatch> observed=new AtomicReference<>();
        Pause arm(String kind) { var pause=new Pause(kind); assertTrue(armed.compareAndSet(null,pause)); return pause; }
        CountDownLatch observeUserLock() { var latch=new CountDownLatch(1); assertTrue(observed.compareAndSet(null,latch)); return latch; }
        @Override public String inspect(String sql) {
            String lower=sql.toLowerCase(Locale.ROOT); boolean userLock=lower.startsWith("select id from pubburi.t_user ")&&lower.contains("for update");
            if(userLock) { var latch=observed.getAndSet(null); if(latch!=null)latch.countDown(); }
            var pause=armed.get(); boolean match=pause!=null&&(pause.kind.equals("values")?lower.startsWith("select ")&&lower.contains(" from pubburi.t_user_preference_value "):pause.kind.equals("delete")?lower.startsWith("delete from pubburi.t_user_preference_value"):userLock);
            if(match&&armed.compareAndSet(pause,null)) { pause.entered.countDown(); try { if(!pause.release.await(6,TimeUnit.SECONDS))throw new IllegalStateException("owned preference gate deadline"); } catch(InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); } }
            return sql;
        }
    }
    @TestConfiguration(proxyBeanMethods=false) static class Probes {
        @Bean Gate preferenceGate() { return new Gate(); }
        @Bean HibernatePropertiesCustomizer preferenceProbe(Gate gate) { return values -> values.put("hibernate.session_factory.statement_inspector",gate); }
    }
}
