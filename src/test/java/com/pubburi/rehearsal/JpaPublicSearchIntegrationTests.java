package com.pubburi.rehearsal;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.pubburi.pub.controller.api.*;
import com.pubburi.pub.controller.response.ResponseMapper;
import com.pubburi.pub.model.dao.ProductSearchIdentityDao;
import com.pubburi.pub.model.service.ProductService;
import com.pubburi.pub.search.ProductSearchGateway;
import com.sun.net.httpserver.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
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
@Import(JpaPublicSearchIntegrationTests.Probes.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfEnvironmentVariable(named="PUBBURI_JPA_INTEGRATION",matches="1")
class JpaPublicSearchIntegrationTests extends JpaAccountTestSupport {
    static final String INDEX="pubburi-products-v1-test";
    static Remote engine;
    @Autowired ObservedGateway observed;
    @Autowired ProductService products;
    @Autowired ProductSearchIdentityDao identities;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry values)throws Exception{engine=new Remote();values.add("pubburi.search.enabled",()->true);values.add("pubburi.search.endpoint",engine::origin);values.add("pubburi.search.index",()->INDEX);}
    @BeforeEach void reset(){engine.reset();engine.source=required("PUBBURI_JPA_SOURCE_ID");observed.insideTransaction.set(null);}
    @AfterAll static void stop(){if(engine!=null)engine.close();}
    record Search(int status,JsonNode body,String source,String hint){}
    Search search(String query)throws Exception{
        var response=client().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/products"+query)).timeout(Duration.ofSeconds(8)).header("Accept","application/json").GET().build(),HttpResponse.BodyHandlers.ofString());
        return new Search(response.statusCode(),json.readTree(response.body()),response.headers().firstValue("X-Pubburi-Search-Source").orElse(null),
            response.headers().firstValue("X-Pubburi-Search-Hint").orElse(null));
    }
    String token(){return UUID.randomUUID().toString().replace("-","");}
    void selected(List<Integer> ids,int total){engine.ids=ids;engine.total=total;}
    void remove(HttpClient admin,List<Integer> ids)throws Exception{for(int id:ids)assertTrue(Set.of(200,404).contains(request(admin,"DELETE","/api/admin/products/"+id,null).status()));}
    JsonNode canonical(String q,String sort,ProductSearchFilters filters,PageCriteria page)throws Exception{return json.readTree(json.writeValueAsString(products.searchProducts(null,q,sort,filters,page).map(ResponseMapper::product)));}

    @Test void existingPublicPageKeepsCurrentFieldsAndAllLiteralFiltersWhileTheRemoteIsOutsideTransactions()throws Exception{
        var admin=loggedIn("ADMIN");String token=token();var ids=new ArrayList<Integer>();
        try{
            for(String suffix:List.of("a","b")){int id=create(admin,"ci_jpa_write_"+token+suffix);ids.add(id);assertEquals(200,request(admin,"PUT","/api/admin/products/"+id+"/features",Map.of("tasteTags",List.of("a,b","line\nx"),"aromaTags",List.of(),"sourceName","Authored search fixture")).status());}
            assertEquals(required("PUBBURI_JPA_SOURCE_ID"),identities.selectSourceId());var filters=ProductSearchFilters.fromQuery("0","2400","0","15.50",new String[]{"a,b","line\nx"},null);
            String query="?q="+token+"&minPriceKrw=0&maxPriceKrw=2400&minAbvPercent=0&maxAbvPercent=15.50&tasteTag=a%2Cb&tasteTag=line%0Ax&sort=priceAsc&size=1";
            for(int page:List.of(1,2)){
                selected(List.of(ids.get(page-1)),2);var result=search(query+"&page="+page);assertEquals(200,result.status());assertEquals("ENGINE_VERIFIED",result.source());assertEquals(canonical(token,"priceAsc",filters,PageCriteria.of(page,1,12)),result.body().path("data"));
                assertEquals(Boolean.FALSE,observed.insideTransaction.get());assertNull(engine.cookie.get());assertNull(engine.authorization.get());
            }
            var sent=engine.last.get();assertTrue(sent.path("track_total_hits").booleanValue());assertFalse(sent.path("_source").booleanValue());String body=sent.toString();assertTrue(body.contains("taste_known"));assertTrue(body.contains("a,b"));assertTrue(body.contains("line\\nx"));assertFalse(body.contains("userId"));
            int calls=engine.calls.get();assertEquals(400,search("?tasteTag=a&tasteTag=A").status());assertEquals(400,search("?minPriceKrw=2&maxPriceKrw=1").status());assertEquals(400,search("?page=2147483647&size=100").status());assertEquals(calls,engine.calls.get());
        }finally{remove(admin,ids);}
    }
    @Test void missingReorderedCountDriftAndWrongSourceGenerationUseTheSameCanonicalSnapshot()throws Exception{
        var admin=loggedIn("ADMIN");String token=token();var ids=new ArrayList<Integer>();
        try{
            ids.add(create(admin,"ci_jpa_write_"+token+"a"));ids.add(create(admin,"ci_jpa_write_"+token+"b"));var expected=canonical(token,"popular",ProductSearchFilters.none(),PageCriteria.of(1,12,12));
            for(String mode:List.of("missing","order","count","source","generation","partial","timedOut","status")){
                engine.mode=mode;engine.metadata.set(0);selected(mode.equals("order")?List.of(ids.get(1),ids.get(0)):mode.equals("missing")?List.of():ids,mode.equals("missing")?0:mode.equals("count")?3:2);
                var result=search("?q="+token);assertEquals(200,result.status());assertEquals("DATABASE_FALLBACK",result.source(),mode);assertEquals(expected,result.body().path("data"),mode);
            }
        }finally{remove(admin,ids);}
    }
    @Test void actualPriceTagAndDeleteCommitsDuringRemoteWaitCannotEscapeTheCurrentMandatoryConditions()throws Exception{
        var admin=loggedIn("ADMIN");var executor=Executors.newSingleThreadExecutor();
        try{for(String mode:List.of("price","tag","delete")){
            String token=token();int id=create(admin,"ci_jpa_write_"+token);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
            try{
                assertEquals(200,request(admin,"PUT","/api/admin/products/"+id+"/features",Map.of("tasteTags",List.of("before"),"sourceName","Authored search fixture")).status());selected(List.of(id),1);engine.entered=entered;engine.release=release;
                String query="?q="+token+"&maxPriceKrw=1200&tasteTag=before";var pending=executor.submit(()->search(query));assertTrue(entered.await(3,TimeUnit.SECONDS));
                if(mode.equals("price"))assertEquals(200,request(admin,"PUT","/api/admin/products/"+id,product("ci_jpa_write_"+token,9000)).status());
                else if(mode.equals("tag"))assertEquals(200,request(admin,"PUT","/api/admin/products/"+id+"/features",Map.of("tasteTags",List.of("after"),"sourceName","Authored search fixture")).status());
                else assertEquals(200,request(admin,"DELETE","/api/admin/products/"+id,null).status());
                release.countDown();var result=pending.get(5,TimeUnit.SECONDS);assertEquals(200,result.status());assertEquals("DATABASE_FALLBACK",result.source());assertEquals(0,result.body().at("/data/total").intValue());assertTrue(result.body().at("/data/items").isEmpty());assertEquals(Boolean.FALSE,observed.insideTransaction.get());
            }finally{release.countDown();engine.entered=null;engine.release=null;remove(admin,List.of(id));}
        }}finally{executor.shutdownNow();assertTrue(executor.awaitTermination(3,TimeUnit.SECONDS));}
    }
    @Test void legitimateEmptyAndUnsupportedLegacyLikeOrLargeOffsetsKeepDifferentEngineStates()throws Exception{
        String token=token();selected(List.of(),0);var empty=search("?q="+token);assertEquals(200,empty.status());assertEquals("ENGINE_VERIFIED",empty.source());assertEquals(0,empty.body().at("/data/total").intValue());
        for(String query:List.of("?q=%25","?q=%5F","?q=a%5Cb","?page=101&size=100")){
            int calls=engine.calls.get();var result=search(query);assertEquals(200,result.status());assertEquals("DATABASE_FALLBACK",result.source());assertEquals(calls,engine.calls.get());
        }
        engine.mode="status";var failed=search("?q="+token);assertEquals(200,failed.status());assertEquals("DATABASE_FALLBACK",failed.source());assertEquals(empty.body(),failed.body());
    }

    @Test void oneTypoHintUsesTheSameCurrentTypePriceAbvAndAllRequiredTags() throws Exception {
        var admin = loggedIn("ADMIN");
        String tag = token();
        var product = new HashMap<>(product("ci_jpa_write_" + tag, 1200));
        product.put("type", "과실주");
        product.put("abv", 7.25);
        var created = request(admin, "POST", "/api/admin/products", product);
        assertEquals(201, created.status());
        int id = created.body().at("/data/id").intValue();
        try {
            assertEquals(200, request(admin, "PUT", "/api/admin/products/" + id + "/features", Map.of(
                "tasteTags", List.of(tag, "dry,fruit"), "aromaTags", List.of("floral"), "sourceName", "Authored hint fixture")).status());
            String typo = URLEncoder.encode("과실쥬", StandardCharsets.UTF_8);
            String canonical = URLEncoder.encode("과실주", StandardCharsets.UTF_8);
            String query = "?q=" + typo + "&type=" + canonical + "&maxPriceKrw=1200&maxAbvPercent=7.25"
                + "&tasteTag=" + tag + "&tasteTag=dry%2Cfruit&aromaTag=floral&sort=priceDesc&size=1";
            selected(List.of(), 0);
            var hinted = search(query);
            assertEquals(200, hinted.status());
            assertEquals("ENGINE_VERIFIED", hinted.source());
            assertEquals("TYPO:%EA%B3%BC%EC%8B%A4%EC%A3%BC", hinted.hint());
            assertEquals(json.valueToTree(PageResponse.of(List.of(), new PageCriteria(1, 1), 0)), hinted.body().path("data"));
            assertTrue(engine.last.get().toString().contains("과실쥬"), "The engine receives the original query only");

            for (String blocked : List.of(query.replace("type=" + canonical, "type=" + URLEncoder.encode("맥주", StandardCharsets.UTF_8)),
                    query.replace("maxPriceKrw=1200", "maxPriceKrw=1199"), query.replace("maxAbvPercent=7.25", "maxAbvPercent=7.24"),
                    query + "&tasteTag=extra", query + "&aromaTag=extra")) {
                var empty = search(blocked);
                assertEquals(200, empty.status());
                assertEquals(hinted.body(), empty.body());
                assertNull(empty.hint());
            }

            engine.mode = "status";
            var fallback = search(query);
            assertEquals("DATABASE_FALLBACK", fallback.source());
            assertEquals(hinted.body(), fallback.body());
            assertEquals(hinted.hint(), fallback.hint());
        } finally {
            remove(admin, List.of(id));
        }
    }
    static final class ObservedGateway implements ProductSearchGateway{
        final ProductSearchGateway delegate;final AtomicReference<Boolean> insideTransaction=new AtomicReference<>();
        ObservedGateway(ProductSearchGateway delegate){this.delegate=delegate;}
        public boolean enabled(){return delegate.enabled();}
        public Optional<Selection> search(String source,String type,String q,String sort,ProductSearchFilters filters,PageCriteria page){insideTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());return delegate.search(source,type,q,sort,filters,page);}
    }
    @TestConfiguration(proxyBeanMethods=false)static class Probes{@Bean @Primary ObservedGateway observed(@Qualifier("productSearchGateway")ProductSearchGateway delegate){return new ObservedGateway(delegate);}}
    static final class Remote implements AutoCloseable{
        final HttpServer http;final ExecutorService executor=Executors.newCachedThreadPool();final AtomicInteger calls=new AtomicInteger(),metadata=new AtomicInteger();final AtomicReference<JsonNode> last=new AtomicReference<>();final AtomicReference<String> cookie=new AtomicReference<>(),authorization=new AtomicReference<>();volatile String source,mode;volatile List<Integer> ids;volatile int total;volatile CountDownLatch entered,release;
        Remote()throws Exception{http=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);http.setExecutor(executor);reset();http.createContext("/",exchange->{try{
            calls.incrementAndGet();cookie.set(exchange.getRequestHeaders().getFirst("Cookie"));authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));var mapper=new com.fasterxml.jackson.databind.ObjectMapper();Object body;
            if(exchange.getRequestMethod().equals("GET")){int n=metadata.incrementAndGet();String current=mode.equals("source")?"00000000-0000-0000-0000-000000000000":source;body=Map.of(INDEX,Map.of("settings",Map.of("index",Map.of("uuid",mode.equals("generation")&&n%2==0?"new-generation":"physical")),"mappings",Map.of("_meta",Map.of("owner","pubburi","schema","products-v1","data_kind","backend_product_events","source_id",current))));}
            else{last.set(mapper.readTree(exchange.getRequestBody()));var gate=entered;var leave=release;if(gate!=null){gate.countDown();assertTrue(leave.await(4,TimeUnit.SECONDS));}body=Map.of("timed_out",mode.equals("timedOut"),"_shards",Map.of("total",1,"successful",1,"failed",mode.equals("partial")?1:0),"hits",Map.of("total",Map.of("value",total,"relation","eq"),"hits",ids.stream().map(id->Map.of("_index",INDEX,"_id",id.toString())).toList()));}
            byte[] bytes=mapper.writeValueAsBytes(body);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(mode.equals("status")?503:200,bytes.length);try(var output=exchange.getResponseBody()){output.write(bytes);}
        }catch(Exception error){exchange.close();}});http.start();}
        void reset(){source="00000000-0000-0000-0000-000000000000";mode="normal";ids=List.of();total=0;entered=null;release=null;calls.set(0);metadata.set(0);last.set(null);}
        String origin(){return "http://127.0.0.1:"+http.getAddress().getPort();}
        public void close(){if(release!=null)release.countDown();http.stop(0);executor.shutdownNow();try{assertTrue(executor.awaitTermination(3,TimeUnit.SECONDS));}catch(InterruptedException error){Thread.currentThread().interrupt();throw new IllegalStateException(error);}}
    }
}
