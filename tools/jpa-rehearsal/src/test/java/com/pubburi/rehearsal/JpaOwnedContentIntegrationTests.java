package com.pubburi.rehearsal;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.pubburi.pub.controller.response.UserPreferencesResponse;
import com.pubburi.pub.model.service.OwnedContentSnapshotService;
import com.pubburi.pub.recommendation.*;
import com.sun.net.httpserver.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.boot.test.context.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.context.annotation.*;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"server.port=0","spring.jpa.properties.hibernate.generate_statistics=true"})
@Import(JpaOwnedContentIntegrationTests.Probes.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfEnvironmentVariable(named="PUBBURI_JPA_INTEGRATION",matches="1")
class JpaOwnedContentIntegrationTests extends JpaAccountTestSupport {
    static final String PATH="/api/users/me/recommendations/content",PREFS="/api/users/me/preferences";
    static Remote remote;
    @Autowired Source source;@Autowired Observed observed;@Autowired OwnedContentSnapshotService snapshots;@Autowired Gate gate;
    @Autowired jakarta.persistence.EntityManagerFactory entityManagerFactory;
    @DynamicPropertySource static void origin(DynamicPropertyRegistry values)throws Exception{remote=new Remote();values.add("pubburi.recommendation.base-url",remote::origin);}
    @BeforeEach void reset(){source.value.set(Optional.empty());remote.calls.set(0);remote.last.set(null);observed.inside.set(null);gate.armed.set(false);remote.handler.set((exchange,body)->remote.reply(exchange,200,zero(body)));}
    @AfterAll static void close(){if(remote!=null)remote.close();}
    class Fixture implements AutoCloseable {
        final java.net.http.HttpClient admin=loggedIn("ADMIN");final String user=join();final java.net.http.HttpClient owner=login(user,password);final List<Integer> ids=new ArrayList<>();
        Fixture()throws Exception{try{for(int i=0;i<2;i++){var body=new HashMap<String,Object>(product(ownName(),1000+i*1000));body.put("type",i==0?"맥주":"증류주");body.put("abv",i*10);var created=request(admin,"POST","/api/admin/products",body);assertEquals(201,created.status());int id=created.body().at("/data/id").intValue();ids.add(id);assertEquals(200,request(admin,"PUT","/api/admin/products/"+id+"/features",Map.of("volumeMl",750-i*250,"tasteTags",i==0?List.of("a,b","line\nx"):List.of(),"aromaTags",List.of(),"sourceName","Authored B1 consumer fixture")).status());}bind();}catch(Throwable error){close();throw error;}}
        void bind()throws Exception{var catalog=json.createArrayNode();var bindings=new LinkedHashMap<Integer,ContentCatalogSource.Binding>();for(int id:ids){var p=request(owner,"GET","/api/products/"+id,null).body().path("data");var f=request(owner,"GET","/api/products/"+id+"/features",null).body().path("data");var row=catalog.addObject();row.put("product_id",id);row.put("available",true);row.put("sku_group","authored-"+id);row.set("category",p.get("type"));row.set("price_krw",p.get("price"));row.set("abv_percent",p.get("abv"));row.set("volume_ml",f.get("volumeMl"));row.putNull("taste_tags");row.putNull("aroma_tags");bindings.put(id,new ContentCatalogSource.Binding(id,p.deepCopy(),f.deepCopy()));}source.value.set(Optional.of(new ContentCatalogSource.Catalog(catalog,Collections.unmodifiableMap(bindings),Instant.now().plusSeconds(300),"d".repeat(64))));}
        @Override public void close(){source.value.set(Optional.empty());try{for(int id:ids){var deleted=request(admin,"DELETE","/api/admin/products/"+id,null);assertTrue(deleted.status()==200 || deleted.status()==404);}clean(control(),user,null,List.of());}catch(Exception error){throw new IllegalStateException(error);}}
    }
    @Test void strictPrivateInputAndOnlyTheCurrentAccountSnapshotReachTheCheckedCatalog()throws Exception{
        try(var f=new Fixture()){
            var state=accountState(control(),f.user);var identity=sourceIdentity(control());assertEquals(401,request(client(),"POST",PATH,Map.of()).status());assertEquals(0,remote.calls.get());
            var result=request(f.owner,"POST",PATH,Map.of());assertEquals(200,result.status());assertEquals("PROVIDER",result.body().at("/data/source").textValue());assertEquals("REVIEWED_DEMO",result.body().at("/data/catalogScope").textValue());assertEquals(f.ids,result.body().at("/data/products").findValues("id").stream().map(JsonNode::intValue).toList());
            assertEquals(Set.of("catalog","preferences","constraints","limit","include_explanations"),keys(remote.last.get()));assertEquals(f.ids,remote.last.get().path("catalog").findValues("product_id").stream().map(JsonNode::intValue).toList());assertEquals("NOT_STARTED",remote.last.get().at("/preferences/surveyStatus").textValue());assertEquals(4,remote.last.get().path("limit").intValue());assertTrue(remote.last.get().path("include_explanations").booleanValue());assertNull(remote.cookie.get());assertNull(remote.authorization.get());assertEquals(Boolean.FALSE,observed.inside.get());
            int calls=remote.calls.get();for(String body:List.of("{\"userId\":\"other\"}","{\"profile\":{}}","{\"preferences\":{}}","{\"catalog\":[]}","{\"limit\":true}","{\"limit\":\"1\"}","{\"limit\":1.0}","{\"limit\":null}","{\"limit\":0}","{\"limit\":13}"))assertEquals(400,request(f.owner,"POST",PATH,body).status());assertEquals(400,request(f.owner,"POST",PATH+"?userId=other",Map.of()).status());assertEquals(calls,remote.calls.get());
            source.value.set(Optional.empty());var unavailable=request(f.owner,"POST",PATH,Map.of());assertEquals(200,unavailable.status());assertEquals("UNAVAILABLE",unavailable.body().at("/data/source").textValue());assertTrue(unavailable.body().at("/data/result").isNull());assertEquals(0,unavailable.body().at("/data/products").size());assertEquals(calls,remote.calls.get());assertEquals(state,accountState(control(),f.user));assertEquals(identity,sourceIdentity(control()));
        }
    }
    @Test void storedZeroUnknownAndHardBoundsRemainExactAndTamperedFactsNeverBecomeRecommendations()throws Exception{
        try(var f=new Fixture()){
            assertEquals(200,request(f.owner,"PUT",PREFS,"{\"surveyStatus\":\"COMPLETED\",\"softPreferences\":{\"tasteTags\":[],\"aromaTags\":null,\"targetAbvPercent\":0.00},\"hardConstraints\":{\"minPriceKrw\":0,\"maxPriceKrw\":0,\"minAbvPercent\":0.00,\"maxAbvPercent\":10.00}}").status());
            remote.handler.set((exchange,body)->{assertEquals(request(f.owner,"GET",PREFS,null).body().path("data"),body.path("preferences"));assertTrue(body.at("/preferences/softPreferences/aromaTags").isNull());assertTrue(body.at("/preferences/softPreferences/tasteTags").isArray());assertEquals(0,body.at("/preferences/softPreferences/targetAbvPercent").decimalValue().signum());remote.reply(exchange,200,zero(body));});
            // No product meets the exact max-price0 bound; a normal empty provider result remains distinct.
            var state=accountState(control(),f.user);var result=request(f.owner,"POST",PATH,Map.of());assertEquals(200,result.status());assertEquals("PROVIDER",result.body().at("/data/source").textValue());assertEquals(0,result.body().at("/data/result/items").size());assertEquals("no_eligible_products",result.body().at("/data/result/fallback_reason").textValue());
            assertEquals(200,request(f.owner,"PUT",PREFS,Map.of("surveyStatus","NOT_STARTED")).status());state=accountState(control(),f.user);
            for(String mode:List.of("foreign","count","explanation","duplicate","redirect","oversized")){
                remote.handler.set((exchange,body)->{var value=zero(body);if(mode.equals("foreign"))value.withObject("/items/0").put("product_id",1);if(mode.equals("count"))value.put("eligible_count",3);if(mode.equals("explanation"))value.withObject("/items/0/explanation/comparison").put("kind","reference_product");String text=json.writeValueAsString(value);if(mode.equals("duplicate"))text=text.replace("\"algorithm\":","\"algorithm\":\"foreign\",\"algorithm\":");if(mode.equals("oversized"))text+=" ".repeat(ContentHttpClient.MAX_RESPONSE_BYTES);exchange.getResponseHeaders().set("Location","/must-not-follow");remote.reply(exchange,mode.equals("redirect")?302:200,text);});
                int calls=remote.calls.get();var unavailable=request(f.owner,"POST",PATH,Map.of());assertEquals(200,unavailable.status());assertEquals("UNAVAILABLE",unavailable.body().at("/data/source").textValue());assertEquals(calls+1,remote.calls.get());assertEquals(state,accountState(control(),f.user));
            }
        }
    }
    @Test void remoteWaitRechecksAllMappedFactsPreferencesCredentialIncarnationAndSessionLifetime()throws Exception{
        var executor=Executors.newSingleThreadExecutor();
        try{for(String mode:List.of("preferences","price","features","delete","credential","rejoin","logout","otherLogin","name"))try(var f=new Fixture()){
            var entered=new CountDownLatch(1);var release=new CountDownLatch(1);String other=mode.equals("otherLogin")?join():null;
            try{
                remote.handler.set((exchange,body)->{entered.countDown();assertTrue(release.await(4,TimeUnit.SECONDS));remote.reply(exchange,200,zero(body));});var pending=executor.submit(()->request(f.owner,"POST",PATH,Map.of()));assertTrue(entered.await(3,TimeUnit.SECONDS));
                switch(mode){
                    case "preferences"->assertEquals(200,request(login(f.user,password),"PUT",PREFS,Map.of("surveyStatus","COMPLETED","hardConstraints",Map.of("maxPriceKrw",0))).status());
                    case "price"->{var current=json.convertValue(source.value.get().orElseThrow().bindings().get(f.ids.get(0)).product(),new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});current.remove("id");current.put("price",3000);assertEquals(200,request(f.admin,"PUT","/api/admin/products/"+f.ids.get(0),current).status());}
                    case "features"->assertEquals(200,request(f.admin,"PUT","/api/admin/products/"+f.ids.get(0)+"/features",Map.of("volumeMl",750,"tasteTags",List.of(),"sourceName","Changed authored fact")).status());
                    case "delete"->assertEquals(200,request(f.admin,"DELETE","/api/admin/products/"+f.ids.get(0),null).status());
                    case "credential"->assertEquals(200,request(login(f.user,password),"PUT","/api/users/me/password",Map.of("currentPassword",password,"newPassword","Authored-rotated-42")).status());
                    case "rejoin"->{assertEquals(200,request(f.admin,"DELETE","/api/admin/users/"+f.user,null).status());assertEquals(201,request(client(),"POST","/api/users",registration(f.user)).status());}
                    case "logout"->assertEquals(200,request(f.owner,"POST","/api/auth/logout",null).status());
                    case "otherLogin"->assertEquals(200,request(f.owner,"POST","/api/auth/login",Map.of("id",other,"pass",password)).status());
                    case "name"->assertEquals(200,request(login(f.user,password),"PUT","/api/users/me/profile",Map.of("name","Only current display name")).status());
                }
                release.countDown();int expected=Set.of("preferences","price","features","delete").contains(mode)?409:mode.equals("name")?200:401;assertEquals(expected,pending.get(6,TimeUnit.SECONDS).status(),mode);assertEquals(Boolean.FALSE,observed.inside.get());
            }finally{release.countDown();if(other!=null)clean(control(),other,null,List.of());}
        }}finally{executor.shutdownNow();assertTrue(executor.awaitTermination(3,TimeUnit.SECONDS));}
    }
    @Test void snapshotReadsStayBatchedAndRepeatableAcrossProductAndFeatureCommits()throws Exception{
        try(var f=new Fixture()){
            var stats=entityManagerFactory.unwrap(org.hibernate.SessionFactory.class).getStatistics();stats.clear();var initial=snapshots.read(f.user,control().queryForObject("SELECT auth_epoch::text FROM pubburi.t_user WHERE id=?",String.class,f.user),f.ids);assertEquals(5,stats.getPrepareStatementCount());assertEquals(2,initial.products().size());
            String epoch=control().queryForObject("SELECT auth_epoch::text FROM pubburi.t_user WHERE id=?",String.class,f.user);var executor=Executors.newSingleThreadExecutor();gate.entered=new CountDownLatch(1);gate.release=new CountDownLatch(1);gate.armed.set(true);
            try{
                var pending=executor.submit(()->snapshots.read(f.user,epoch,f.ids));assertTrue(gate.entered.await(3,TimeUnit.SECONDS));var body=new HashMap<String,Object>(product(ownName(),3000));body.put("type","맥주");body.put("abv",0);assertEquals(200,request(f.admin,"PUT","/api/admin/products/"+f.ids.get(0),body).status());assertEquals(200,request(f.admin,"PUT","/api/admin/products/"+f.ids.get(0)+"/features",Map.of("volumeMl",750,"tasteTags",List.of(),"sourceName","New authored tags")).status());gate.release.countDown();var old=pending.get(5,TimeUnit.SECONDS);assertEquals(1000,old.products().get(f.ids.get(0)).product().path("price").intValue());assertEquals(2,old.products().get(f.ids.get(0)).features().path("tasteTags").size());var current=snapshots.read(f.user,epoch,f.ids);assertEquals(3000,current.products().get(f.ids.get(0)).product().path("price").intValue());assertEquals(0,current.products().get(f.ids.get(0)).features().path("tasteTags").size());
            }finally{gate.armed.set(false);gate.release.countDown();executor.shutdownNow();assertTrue(executor.awaitTermination(3,TimeUnit.SECONDS));}
        }
    }
    @Test void realReadErrorsBeforeOrAfterRemoteRemain500AndDoNotBecomeEmptyUnavailable()throws Exception{
        try(var f=new Fixture()){
            var sql=control();String role=required("PUBBURI_PG_USER");assertTrue(role.matches("[a-z][a-z0-9_]{0,62}"));assertTrue(Boolean.TRUE.equals(sql.queryForObject("SELECT has_table_privilege(?,'pubburi.t_product','SELECT')",Boolean.class,role)));var state=accountState(sql,f.user);var identity=sourceIdentity(sql);
            try{sql.execute("REVOKE SELECT ON pubburi.t_product FROM \""+role+"\"");int calls=remote.calls.get();assertEquals(500,request(f.owner,"POST",PATH,Map.of()).status());assertEquals(calls,remote.calls.get());}finally{sql.execute("GRANT SELECT ON pubburi.t_product TO \""+role+"\"");}
            try{remote.handler.set((exchange,body)->{sql.execute("REVOKE SELECT ON pubburi.t_product FROM \""+role+"\"");remote.reply(exchange,200,zero(body));});assertEquals(500,request(f.owner,"POST",PATH,Map.of()).status());}finally{sql.execute("GRANT SELECT ON pubburi.t_product TO \""+role+"\"");}
            assertEquals(state,accountState(sql,f.user));assertEquals(identity,sourceIdentity(sql));assertEquals(200,request(f.owner,"GET","/api/products/"+f.ids.get(0),null).status());
        }
    }
    static ObjectNode zero(JsonNode request){
        var mapper=new ObjectMapper();var root=mapper.createObjectNode();root.put("algorithm","b1-tfidf-minmax-v1");root.put("fit_scope","request_catalog");root.put("representation_fitted",true);root.put("model_fingerprint","a".repeat(64));var prefs=request.path("preferences");boolean maxZero=prefs.at("/hardConstraints/maxPriceKrw").isNumber() && prefs.at("/hardConstraints/maxPriceKrw").intValue()==0;boolean numeric=prefs.at("/softPreferences/targetAbvPercent").isNumber();
        root.put("eligible_count",maxZero?0:request.path("catalog").size());root.put("supported_profile_tokens",0);root.put("unknown_profile_tokens",0);root.putArray("active_numeric_preferences");if(numeric)root.withArray("active_numeric_preferences").add("abv_percent");root.put("fallback_reason",maxZero?"no_eligible_products":"no_supported_preferences");var items=root.putArray("items");if(!maxZero)for(var row:request.path("catalog")){var item=items.addObject();item.set("product_id",row.get("product_id"));item.put("score",0);item.putArray("reasons").add("available").add("deterministic_baseline");item.putArray("contributions");var e=item.putObject("explanation");e.put("version","r04-facts-v1");e.put("basis","deterministic_baseline");e.putObject("comparison").put("kind","profile");e.putArray("score_evidence");e.put("total_positive_contributions",0);e.put("score_evidence_truncated",false);e.putArray("filter_evidence").addObject().put("kind","availability_filter").put("label","입력 가용 조건 충족").put("actual_value",true);e.put("fallback_reason","no_supported_preferences");}return root;
    }
    static class Source implements ContentCatalogSource {final AtomicReference<Optional<Catalog>> value=new AtomicReference<>(Optional.empty());@Override public Optional<Catalog> current(){return value.get();}}
    static class Observed implements ContentGateway {final ContentGateway delegate;final AtomicReference<Boolean> inside=new AtomicReference<>();Observed(ContentGateway delegate){this.delegate=delegate;}@Override public Optional<JsonNode> recommend(JsonNode c,UserPreferencesResponse p,int l){inside.set(TransactionSynchronizationManager.isActualTransactionActive());return delegate.recommend(c,p,l);}}
    static class Gate implements StatementInspector {final AtomicBoolean armed=new AtomicBoolean();volatile CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);@Override public String inspect(String sql){if(sql.contains("left join") && sql.contains("t_product_feature") && armed.compareAndSet(true,false)){entered.countDown();try{if(!release.await(4,TimeUnit.SECONDS))throw new IllegalStateException("Owned RR test gate deadline");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}}return sql;}}
    @TestConfiguration(proxyBeanMethods=false) static class Probes {@Bean @Primary Source source(){return new Source();}@Bean @Primary Observed observed(@Qualifier("contentGateway")ContentGateway gateway){return new Observed(gateway);}@Bean Gate gate(){return new Gate();}@Bean HibernatePropertiesCustomizer gates(Gate gate){return properties->properties.put("hibernate.session_factory.statement_inspector",gate);}}
    @FunctionalInterface interface Handler {void handle(HttpExchange exchange,JsonNode body)throws Exception;}
    static class Remote implements AutoCloseable {
        final HttpServer server;final ExecutorService executor=Executors.newCachedThreadPool();final AtomicReference<Handler> handler=new AtomicReference<>();final AtomicInteger calls=new AtomicInteger();final AtomicReference<JsonNode> last=new AtomicReference<>();final AtomicReference<String> cookie=new AtomicReference<>(),authorization=new AtomicReference<>();
        Remote()throws Exception{server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setExecutor(executor);server.createContext("/",exchange->{try{assertEquals("/v1/recommendations/content",exchange.getRequestURI().toString());calls.incrementAndGet();last.set(new ObjectMapper().readTree(exchange.getRequestBody().readAllBytes()));cookie.set(exchange.getRequestHeaders().getFirst("Cookie"));authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));handler.get().handle(exchange,last.get());}catch(Throwable error){exchange.close();}});server.start();}
        String origin(){return "http://127.0.0.1:"+server.getAddress().getPort();}void reply(HttpExchange exchange,int status,JsonNode body)throws Exception{reply(exchange,status,new ObjectMapper().writeValueAsString(body));}void reply(HttpExchange exchange,int status,String body)throws Exception{byte[] bytes=body.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(status,bytes.length);try(var output=exchange.getResponseBody()){output.write(bytes);}}@Override public void close(){server.stop(0);executor.shutdownNow();try{assertTrue(executor.awaitTermination(3,TimeUnit.SECONDS));}catch(InterruptedException error){Thread.currentThread().interrupt();throw new IllegalStateException(error);}}
    }
}
