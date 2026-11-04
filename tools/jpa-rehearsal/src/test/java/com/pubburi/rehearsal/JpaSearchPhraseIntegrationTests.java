package com.pubburi.rehearsal;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.pubburi.pub.controller.response.UserPreferencesResponse;
import com.pubburi.pub.recommendation.SearchPhraseGateway;
import com.sun.net.httpserver.*;
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
@Import(JpaSearchPhraseIntegrationTests.Probes.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfEnvironmentVariable(named="PUBBURI_JPA_INTEGRATION",matches="1")
class JpaSearchPhraseIntegrationTests extends JpaAccountTestSupport {
    static final String PATH="/api/users/me/search-phrases",PREFS="/api/users/me/preferences";
    static final String ALL="""
        {"algorithm":"r06-explicit-search-phrases-v1","items":[{"template_id":"all_products","label":"전체 상품 보기","conditions":{"type":null,"minPriceKrw":null,"maxPriceKrw":null,"minAbvPercent":null,"maxAbvPercent":null,"tasteTag":[],"aromaTag":[]},"query_pairs":[],"backend_unicode_revalidation_required":false}],"candidate_count":1,"omitted_count":0,"product_count":null,"diagnostics":[],"fallback_reason":"no_explicit_search_conditions"}
        """;
    static Remote upstream;
    @Autowired ObservedGateway observed;
    @DynamicPropertySource static void remote(DynamicPropertyRegistry values) throws Exception {upstream=new Remote();values.add("pubburi.recommendation.base-url",upstream::origin);}
    @BeforeEach void reset(){upstream.handler.set((exchange,body)->upstream.reply(exchange,200,ALL));upstream.calls.set(0);upstream.last.set(null);observed.insideTransaction.set(null);}
    @AfterAll static void stop(){if(upstream!=null)upstream.close();}

    @Test void privateInputUsesOnlyTheCurrentEpochAndNeverChangesStoredPreferences() throws Exception {
        var sql=control();String user=join(),other=join();var admin=loggedIn("ADMIN");
        try {
            var owner=login(user,password);var stranger=login(other,password);var before=accountState(sql,user);var identity=sourceIdentity(sql);
            assertEquals(401,request(client(),"POST",PATH,Map.of()).status());assertEquals(0,upstream.calls.get());
            for(var caller:List.of(owner,stranger,admin)) {
                var response=request(caller,"POST",PATH,Map.of());assertEquals(200,response.status());var data=response.body().path("data");
                assertEquals(Set.of("source","result"),keys(data));assertEquals("PROVIDER",data.path("source").textValue());assertEquals(json.readTree(ALL),data.path("result"));
                var sent=upstream.last.get();assertEquals(Set.of("preferences","search_opt_in_fields","limit"),keys(sent));assertEquals("NOT_STARTED",sent.path("preferences").path("surveyStatus").textValue());
                assertEquals(0,sent.path("search_opt_in_fields").size());assertEquals(5,sent.path("limit").intValue());
                assertNull(upstream.cookie.get());assertNull(upstream.authorization.get());assertEquals(Boolean.FALSE,observed.insideTransaction.get());
            }
            int calls=upstream.calls.get();for(String invalid:List.of("{\"userId\":\""+other+"\"}","{\"preferences\":{}}","{\"limit\":null}","{\"limit\":1.0}","{\"limit\":\"1\"}","{\"searchOptInFields\":[null]}","{\"searchOptInFields\":[\"tasteTags\",\"tasteTags\"]}"))assertEquals(400,request(owner,"POST",PATH,invalid).status());
            assertEquals(400,request(owner,"POST",PATH+"?userId="+other,Map.of()).status());assertEquals(calls,upstream.calls.get());assertEquals(before,accountState(sql,user));assertEquals(identity,sourceIdentity(sql));
        }finally{clean(sql,user,null,List.of());clean(sql,other,null,List.of());}
    }

    @Test void currentSnapshotKeepsLiteralTagsZeroScaleAndUnselectedSoftOutOfConditions() throws Exception {
        var sql=control();String user=join();
        try {
            var owner=login(user,password);assertEquals(200,request(owner,"PUT",PREFS,"""
                {"surveyStatus":"COMPLETED","softPreferences":{"categories":["맥주"],"tasteTags":["a,b","line\\nx"],"aromaTags":[],"targetAbvPercent":13.70},"hardConstraints":{"minPriceKrw":0,"maxPriceKrw":20000,"minAbvPercent":0.00,"maxAbvPercent":20.00}}
                """).status());
            String fixed="""
                {"algorithm":"r06-explicit-search-phrases-v1","items":[{"template_id":"selected_preferences","label":"선택한 취향 조건으로 검색","conditions":{"type":null,"minPriceKrw":0,"maxPriceKrw":20000,"minAbvPercent":"0.00","maxAbvPercent":"20.00","tasteTag":["a,b","line\\nx"],"aromaTag":[]},"query_pairs":[{"name":"minPriceKrw","value":"0"},{"name":"maxPriceKrw","value":"20000"},{"name":"minAbvPercent","value":"0.00"},{"name":"maxAbvPercent","value":"20.00"},{"name":"tasteTag","value":"a,b"},{"name":"tasteTag","value":"line\\nx"}],"backend_unicode_revalidation_required":false}],"candidate_count":1,"omitted_count":0,"product_count":null,"diagnostics":[{"field":"categories","code":"soft_not_selected"},{"field":"aromaTags","code":"soft_not_selected"},{"field":"targetAbvPercent","code":"soft_not_selected"}],"fallback_reason":null}
                """;
            upstream.handler.set((exchange,body)->upstream.reply(exchange,200,fixed));var before=accountState(sql,user);
            var response=request(owner,"POST",PATH,Map.of("searchOptInFields",List.of("tasteTags"),"limit",5));assertEquals(200,response.status());assertEquals("PROVIDER",response.body().at("/data/source").textValue());assertEquals(json.readTree(fixed),response.body().at("/data/result"));
            assertEquals(request(owner,"GET",PREFS,null).body().path("data"),upstream.last.get().path("preferences"));assertEquals(before,accountState(sql,user));
            assertTrue(upstream.raw.get().contains("13.70"));assertTrue(upstream.raw.get().contains("20.00"));assertEquals(Boolean.FALSE,observed.insideTransaction.get());
            // Upstream cannot silently drop one hard bound or split a literal atom.
            for(String bad:List.of(fixed.replace("\"maxPriceKrw\":20000","\"maxPriceKrw\":null"),fixed.replace("\"value\":\"a,b\"","\"value\":\"a\""))) {
                upstream.handler.set((exchange,body)->upstream.reply(exchange,200,bad));var unavailable=request(owner,"POST",PATH,Map.of("searchOptInFields",List.of("tasteTags")));
                assertEquals(200,unavailable.status());assertEquals("UNAVAILABLE",unavailable.body().at("/data/source").textValue());assertTrue(unavailable.body().at("/data/result").isNull());assertEquals(before,accountState(sql,user));
            }
        }finally{clean(sql,user,null,List.of());}
    }

    @Test void remoteWaitHoldsNoDbTransactionAndChangedSnapshotOrAccountCannotReceiveItsOldResult() throws Exception {
        var sql=control();var admin=loggedIn("ADMIN");var executor=Executors.newSingleThreadExecutor();
        try {for(String mode:List.of("preferences","credential","rejoin","logout","otherLogin","name")) {
            String user=join(),other=mode.equals("otherLogin")?join():null;var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
            try {
                var owner=login(user,password);var writer=login(user,password);
                upstream.handler.set((exchange,body)->{entered.countDown();assertTrue(release.await(4,TimeUnit.SECONDS));upstream.reply(exchange,200,ALL);});
                var pending=executor.submit(()->request(owner,"POST",PATH,Map.of()));assertTrue(entered.await(3,TimeUnit.SECONDS));
                switch(mode){
                    case "preferences" -> assertEquals(200,request(writer,"PUT",PREFS,Map.of("surveyStatus","COMPLETED","hardConstraints",Map.of("maxPriceKrw",0))).status());
                    case "credential" -> assertEquals(200,request(writer,"PUT","/api/users/me/password",Map.of("currentPassword",password,"newPassword","Authored-rotated-42")).status());
                    case "rejoin" -> {assertEquals(200,request(admin,"DELETE","/api/admin/users/"+user,null).status());assertEquals(201,request(client(),"POST","/api/users",registration(user)).status());}
                    case "logout" -> assertEquals(200,request(owner,"POST","/api/auth/logout",null).status());
                    case "otherLogin" -> assertEquals(200,request(owner,"POST","/api/auth/login",Map.of("id",other,"pass",password)).status());
                    case "name" -> assertEquals(200,request(writer,"PUT","/api/users/me/profile",Map.of("name","Updated current name")).status());
                }
                release.countDown();var result=pending.get(5,TimeUnit.SECONDS);assertEquals(mode.equals("preferences")?409:mode.equals("name")?200:401,result.status(),mode);
                assertEquals(Boolean.FALSE,observed.insideTransaction.get());
            }finally{release.countDown();clean(sql,user,null,List.of());if(other!=null)clean(sql,other,null,List.of());}
        }}finally{executor.shutdownNow();assertTrue(executor.awaitTermination(3,TimeUnit.SECONDS));}
    }

    @Test void providerTransportAndContractErrorsStayUnavailableWithCurrentPrivateIdentity() throws Exception {
        var sql=control();String user=join();
        try {
            var owner=login(user,password);var before=accountState(sql,user);
            for(String mode:List.of("status","redirect","duplicate","trailing","unknown","count","oversized")) {
                String body=switch(mode){case "duplicate" -> ALL.replace("\"algorithm\":","\"algorithm\":\"duplicate\",\"algorithm\":");case "trailing" -> ALL+" {}";case "unknown" -> ALL.replace("r06-explicit-search-phrases-v1","foreign");case "count" -> ALL.replace("\"product_count\":null","\"product_count\":0");case "oversized" -> ALL+" ".repeat(131072);default -> ALL;};
                upstream.handler.set((exchange,payload)->{exchange.getResponseHeaders().set("Location","/must-not-follow");upstream.reply(exchange,mode.equals("status")?503:mode.equals("redirect")?302:200,body);});
                int calls=upstream.calls.get();var result=request(owner,"POST",PATH,Map.of());assertEquals(200,result.status());assertEquals("UNAVAILABLE",result.body().at("/data/source").textValue());assertTrue(result.body().at("/data/result").isNull());assertEquals(calls+1,upstream.calls.get());assertEquals(before,accountState(sql,user));
            }
        }finally{clean(sql,user,null,List.of());}
    }
    @Test void exactStoredUnicodeConditionsRejectedByPublicSearchRemain400WithoutRepair() throws Exception {
        var sql=control();String user=join();
        try {
            var owner=login(user,password);assertEquals(200,request(owner,"PUT",PREFS,Map.of("surveyStatus","COMPLETED","softPreferences",Map.of("tasteTags",List.of("k")))).status());
            // Valid storage types and C-key uniqueness; only this new authored account is altered.
            sql.update("INSERT INTO pubburi.t_user_preference_value(user_id,kind,value_text) VALUES(?,'TASTE','K')",user);
            var before=accountState(sql,user);assertEquals(400,request(client(),"GET","/api/products?tasteTag=k&tasteTag=%E2%84%AA",null).status());
            String fixed="""
                {"algorithm":"r06-explicit-search-phrases-v1","items":[{"template_id":"selected_preferences","label":"선택한 취향 조건으로 검색","conditions":{"type":null,"minPriceKrw":null,"maxPriceKrw":null,"minAbvPercent":null,"maxAbvPercent":null,"tasteTag":["k","K"],"aromaTag":[]},"query_pairs":[{"name":"tasteTag","value":"k"},{"name":"tasteTag","value":"K"}],"backend_unicode_revalidation_required":true}],"candidate_count":1,"omitted_count":0,"product_count":null,"diagnostics":[{"field":"tasteTags","code":"backend_unicode_revalidation_required"}],"fallback_reason":null}
                """;
            upstream.handler.set((exchange,payload)->upstream.reply(exchange,200,fixed));var result=request(owner,"POST",PATH,Map.of("searchOptInFields",List.of("tasteTags")));
            assertEquals(400,result.status());assertEquals("Stored preferences cannot be used as search conditions",result.body().at("/error/message").textValue());assertEquals(before,accountState(sql,user));
            upstream.handler.set((exchange,payload)->upstream.reply(exchange,200,fixed.replace("\"name\":\"tasteTag\"","\"name\":\"url\"")));
            var invalid=request(owner,"POST",PATH,Map.of("searchOptInFields",List.of("tasteTags")));assertEquals(200,invalid.status());assertEquals("UNAVAILABLE",invalid.body().at("/data/source").textValue());assertTrue(invalid.body().at("/data/result").isNull());assertEquals(before,accountState(sql,user));
        }finally{clean(sql,user,null,List.of());}
    }
    static final class ObservedGateway implements SearchPhraseGateway {
        final SearchPhraseGateway delegate;final AtomicReference<Boolean> insideTransaction=new AtomicReference<>();
        ObservedGateway(SearchPhraseGateway delegate){this.delegate=delegate;}
        @Override public Optional<JsonNode> suggest(UserPreferencesResponse preferences,List<String> fields,int limit){insideTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());return delegate.suggest(preferences,fields,limit);}
    }
    @TestConfiguration(proxyBeanMethods=false) static class Probes {
        @Bean @Primary ObservedGateway observed(@Qualifier("searchPhraseGateway") SearchPhraseGateway delegate){return new ObservedGateway(delegate);}
    }
    @FunctionalInterface interface Handler{void handle(HttpExchange exchange,JsonNode body)throws Exception;}
    static final class Remote implements AutoCloseable {
        final HttpServer server;final ExecutorService executor=Executors.newCachedThreadPool();final AtomicReference<Handler> handler=new AtomicReference<>();final AtomicInteger calls=new AtomicInteger();final AtomicReference<JsonNode> last=new AtomicReference<>();final AtomicReference<String> raw=new AtomicReference<>(),cookie=new AtomicReference<>(),authorization=new AtomicReference<>();
        Remote()throws Exception{server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setExecutor(executor);server.createContext("/",exchange->{try{assertEquals("/v1/recommendations/search-phrases",exchange.getRequestURI().toString());assertEquals("POST",exchange.getRequestMethod());calls.incrementAndGet();raw.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));last.set(new com.fasterxml.jackson.databind.ObjectMapper().readTree(raw.get()));cookie.set(exchange.getRequestHeaders().getFirst("Cookie"));authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));handler.get().handle(exchange,last.get());}catch(Exception error){exchange.close();}});server.start();}
        String origin(){return "http://127.0.0.1:"+server.getAddress().getPort();}
        void reply(HttpExchange exchange,int status,String body)throws Exception{byte[] bytes=body.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(status,bytes.length);try(var output=exchange.getResponseBody()){output.write(bytes);}}
        @Override public void close(){server.stop(0);executor.shutdownNow();try{assertTrue(executor.awaitTermination(3,TimeUnit.SECONDS));}catch(InterruptedException error){Thread.currentThread().interrupt();throw new IllegalStateException(error);}}
    }
}
