package com.pubburi.rehearsal;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pubburi.pub.model.dao.ProductDao;
import com.pubburi.pub.recommendation.BaselineGateway;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties="server.port=0")
@Import(JpaDefaultRecommendationIntegrationTests.Probes.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfEnvironmentVariable(named="PUBBURI_JPA_INTEGRATION",matches="1")
class JpaDefaultRecommendationIntegrationTests extends JpaAccountTestSupport {
    static final String PATH="/api/recommendations/default";
    static OwnedB0 upstream;
    @Autowired ProductDao products;
    @Autowired ObservedGateway observed;
    @DynamicPropertySource static void remote(DynamicPropertyRegistry properties) throws IOException {
        upstream=new OwnedB0(); properties.add("pubburi.recommendation.base-url",upstream::origin);
    }
    @BeforeEach void reset() { upstream.reset(); observed.insideTransaction.set(null); }
    @AfterAll static void stopOwnedUpstream() { if(upstream!=null)upstream.close(); }
    List<Integer> ids(JsonNode items) { var ids=new ArrayList<Integer>(); items.forEach(p -> ids.add(p.path("id").intValue())); return ids; }
    void publicItems(Reply result,String source,List<Integer> expected) throws Exception {
        assertEquals(200,result.status()); var data=result.body().path("data"); assertEquals(Set.of("items","source"),keys(data)); assertEquals(source,data.path("source").textValue()); assertEquals(expected,ids(data.path("items")));
        for(var item:data.path("items")) { assertEquals(Set.of("id","name","type","price","img","abv","orderCount"),keys(item)); assertEquals(request(client(),"GET","/api/products/"+item.path("id").intValue(),null).body().path("data"),item); }
    }

    @Test void publicSelectionSizeAndPrivacyUseCurrentJpaProductsWithoutARemoteTransaction() throws Exception {
        var sql=control(); var expected=sql.queryForList("SELECT id FROM pubburi.t_product ORDER BY id LIMIT 1000",Integer.class); var identity=sourceIdentity(sql);
        var ordinary=loggedIn("USER"); var admin=loggedIn("ADMIN");
        for(var client:List.of(client(),ordinary,admin)) for(int size:List.of(1,4,12)) {
            publicItems(request(client,"GET",PATH+"?size="+size+"&userId=untrusted&baseUrl=http://invalid.example",null),"BASELINE",expected.subList(0,Math.min(size,expected.size())));
            var sent=upstream.last.get(); assertEquals(Set.of("catalog","constraints","limit"),keys(sent.body())); assertEquals(size,sent.body().path("limit").intValue()); assertEquals(0,sent.body().path("constraints").size());
            assertEquals(expected,sent.body().path("catalog").findValues("product_id").stream().map(JsonNode::intValue).toList());
            for(var candidate:sent.body().path("catalog")) { assertEquals(Set.of("product_id","available"),keys(candidate)); assertTrue(candidate.path("available").booleanValue()); }
            assertEquals("POST",sent.method()); assertEquals("/v1/recommendations/baseline",sent.path()); assertNull(sent.cookie()); assertNull(sent.authorization()); assertEquals(Boolean.FALSE,observed.insideTransaction.get());
        }
        publicItems(request(client(),"GET",PATH,null),"BASELINE",expected.subList(0,Math.min(4,expected.size())));
        int calls=upstream.calls.get(); for(String invalid:List.of("0","13","-1","1.5","1e2","2147483648","true")) assertEquals(400,request(client(),"GET",PATH+"?size="+invalid,null).status()); assertEquals(calls,upstream.calls.get()); assertEquals(identity,sourceIdentity(sql));
    }

    @Test void candidateCapAndCurrentIdBatchReadUseRealProjectionsWithoutChangingSourceRows() throws Exception {
        var sql=control(); String prefix="ci_jpa_default_"+UUID.randomUUID().toString().replace("-","")+"_";
        try {
            var rows=new ArrayList<Object[]>(); for(int i=0;i<1003;i++)rows.add(new Object[]{prefix+i});
            sql.batchUpdate("INSERT INTO pubburi.t_product(name,type,price,img,abv,order_count) VALUES(?,'증류주',0,'authored.png',0,0)",rows);
            var expected=sql.queryForList("SELECT id FROM pubburi.t_product ORDER BY id LIMIT 1000",Integer.class); assertEquals(1000,expected.size());
            publicItems(request(client(),"GET",PATH+"?size=12",null),"BASELINE",expected.subList(0,12)); assertEquals(expected,upstream.last.get().body().path("catalog").findValues("product_id").stream().map(JsonNode::intValue).toList());
            var owned=sql.queryForList("SELECT id FROM pubburi.t_product WHERE starts_with(name COLLATE \"C\",?) ORDER BY id",Integer.class,prefix); assertEquals(1003,owned.size());
            var found=products.selectCurrentByIds(List.of(owned.get(1002),owned.get(0),Integer.MAX_VALUE)); assertEquals(Set.of(owned.get(0),owned.get(1002)),found.stream().map(p -> p.getId()).collect(java.util.stream.Collectors.toSet()));
            found.forEach(p -> { assertTrue(p.getName().startsWith(prefix)); assertEquals(0,p.getPrice()); assertEquals(0,p.getAbv()); }); assertEquals(List.of(),products.selectCurrentByIds(List.of()));
        } finally { sql.update("DELETE FROM pubburi.t_product WHERE starts_with(name COLLATE \"C\",?)",prefix); }
    }

    @Test void remoteFailuresAndInvalidContractsFallBackToTheCurrentJpaIdOrder() throws Exception {
        var expected=control().queryForList("SELECT id FROM pubburi.t_product ORDER BY id LIMIT 4",Integer.class);
        for(String mode:List.of("status","media","redirect","algorithm","unknownId","duplicate","extra","eligible","reasons")) {
            upstream.handler.set((exchange,payload) -> {
                String valid=upstream.valid(payload); exchange.getResponseHeaders().set("Location","/must-not-follow");
                String body=switch(mode) {
                    case "algorithm" -> valid.replace("b0-id-ascending-v1","unknown-model");
                    case "unknownId" -> valid.replaceFirst("\\\"product_id\\\":\\d+","\"product_id\":2147483647");
                    case "duplicate" -> valid.replace("\"algorithm\":","\"algorithm\":\"duplicate\",\"algorithm\":");
                    case "extra" -> valid+" {}";
                    case "eligible" -> valid.replaceFirst("\\\"eligible_count\\\":\\d+","\"eligible_count\":0");
                    case "reasons" -> valid.replace("deterministic_baseline","inferred_preference");
                    default -> valid;
                };
                upstream.reply(exchange,mode.equals("status")?503:mode.equals("redirect")?302:200,mode.equals("media")?"text/html":"application/json",body,false);
            });
            int calls=upstream.calls.get(); publicItems(request(client(),"GET",PATH,null),"FALLBACK",expected); assertEquals(calls+1,upstream.calls.get());
        }
        assertEquals(Boolean.FALSE,observed.insideTransaction.get());
    }

    @Test void actualConfiguredBodyCapAcceptsTheBoundaryAndRejectsOneExtraByte() throws Exception {
        var expected=control().queryForList("SELECT id FROM pubburi.t_product ORDER BY id LIMIT 4",Integer.class);
        for(int size:List.of(32768,32769)) {
            upstream.handler.set((exchange,payload) -> { String valid=upstream.valid(payload); upstream.reply(exchange,200,"application/json",valid+" ".repeat(size-valid.getBytes(StandardCharsets.UTF_8).length),true); });
            publicItems(request(client(),"GET",PATH,null),size==32768?"BASELINE":"FALLBACK",expected);
        }
    }

    @Test void unfinishedBodyCompletesFallbackWithinTheConfiguredWholeBodyDeadline() throws Exception {
        var expected=control().queryForList("SELECT id FROM pubburi.t_product ORDER BY id LIMIT 4",Integer.class); var entered=new CountDownLatch(1); var release=new CountDownLatch(1); var executor=Executors.newSingleThreadExecutor();
        upstream.handler.set((exchange,payload) -> {
            exchange.getResponseHeaders().set("Content-Type","application/json"); exchange.sendResponseHeaders(200,0);
            try(var output=exchange.getResponseBody()) { output.write("{\"algorithm\":".getBytes(StandardCharsets.UTF_8)); output.flush(); entered.countDown(); try { release.await(6,TimeUnit.SECONDS); } catch(InterruptedException error) { Thread.currentThread().interrupt(); } }
        });
        try {
            var result=executor.submit(() -> request(client(),"GET",PATH,null)); assertTrue(entered.await(3,TimeUnit.SECONDS)); publicItems(result.get(3,TimeUnit.SECONDS),"FALLBACK",expected); assertEquals(1,release.getCount());
        } finally { release.countDown(); executor.shutdownNow(); assertTrue(executor.awaitTermination(3,TimeUnit.SECONDS)); }
    }

    static final class ObservedGateway implements BaselineGateway {
        final BaselineGateway delegate; final AtomicReference<Boolean> insideTransaction=new AtomicReference<>();
        ObservedGateway(BaselineGateway delegate) { this.delegate=delegate; }
        @Override public Optional<List<Integer>> recommend(List<Integer> candidates,int limit) { insideTransaction.set(TransactionSynchronizationManager.isActualTransactionActive()); return delegate.recommend(candidates,limit); }
    }
    @TestConfiguration(proxyBeanMethods=false) static class Probes {
        @Bean @Primary ObservedGateway observedGateway(@Qualifier("baselineGateway") BaselineGateway delegate) { return new ObservedGateway(delegate); }
    }
    record Captured(String method,String path,String cookie,String authorization,JsonNode body) { }
    @FunctionalInterface interface Handler { void handle(HttpExchange exchange,JsonNode payload) throws Exception; }
    static final class OwnedB0 implements AutoCloseable {
        final ObjectMapper mapper=new ObjectMapper(); final HttpServer server; final ExecutorService executor=Executors.newCachedThreadPool(r -> { var t=new Thread(r,"owned-jpa-b0-fixture");t.setDaemon(true);return t; });
        final AtomicInteger calls=new AtomicInteger(); final AtomicReference<Captured> last=new AtomicReference<>(); final AtomicReference<Handler> handler=new AtomicReference<>();
        OwnedB0() throws IOException {
            server=HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),0),0); server.setExecutor(executor); reset();
            server.createContext("/",exchange -> { try { var payload=mapper.readTree(exchange.getRequestBody()); calls.incrementAndGet(); last.set(new Captured(exchange.getRequestMethod(),exchange.getRequestURI().toString(),exchange.getRequestHeaders().getFirst("Cookie"),exchange.getRequestHeaders().getFirst("Authorization"),payload)); handler.get().handle(exchange,payload); } catch(Exception expectedCancellation) { exchange.close(); } }); server.start();
        }
        String origin() { return "http://127.0.0.1:"+server.getAddress().getPort(); }
        void reset() { calls.set(0); last.set(null); handler.set((exchange,payload) -> reply(exchange,200,"application/json",valid(payload),false)); }
        String valid(JsonNode payload) throws IOException {
            var items=new ArrayList<Map<String,Object>>(); var catalog=payload.path("catalog"); for(int i=0;i<Math.min(payload.path("limit").intValue(),catalog.size());i++)items.add(Map.of("product_id",catalog.get(i).path("product_id").intValue(),"reasons",List.of("available","deterministic_baseline")));
            var response=new LinkedHashMap<String,Object>(); response.put("algorithm","b0-id-ascending-v1");response.put("items",items);response.put("eligible_count",catalog.size());response.put("fallback_reason",null);return mapper.writeValueAsString(response);
        }
        void reply(HttpExchange exchange,int status,String media,String text,boolean chunked) throws IOException {
            byte[] bytes=text.getBytes(StandardCharsets.UTF_8); exchange.getResponseHeaders().set("Content-Type",media);exchange.sendResponseHeaders(status,chunked?0:bytes.length);try(var output=exchange.getResponseBody()) { output.write(bytes); }
        }
        @Override public void close() { server.stop(0);executor.shutdownNow();try { assertTrue(executor.awaitTermination(3,TimeUnit.SECONDS)); } catch(InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); } }
    }
}
