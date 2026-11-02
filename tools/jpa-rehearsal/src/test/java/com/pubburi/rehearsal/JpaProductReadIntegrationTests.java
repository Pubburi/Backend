package com.pubburi.rehearsal;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Real PG/HTTP checks; authored rows are owned by each fixture and no copied source row is modified. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties="server.port=0")
@Import(JpaProductReadIntegrationTests.Probes.class)
@EnabledIfEnvironmentVariable(named="PUBBURI_JPA_INTEGRATION",matches="1")
class JpaProductReadIntegrationTests {
    @LocalServerPort int port;
    @Autowired JdbcTemplate runtime;
    @Autowired QueryGate gate;
    final ObjectMapper json=new ObjectMapper();
    final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    record Reply(int status,JsonNode body) { }

    Reply get(String path)throws Exception {return request("GET",path,null);}
    Reply request(String method,String path,String accept)throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(8))
            .header("Accept",accept==null?"application/json":accept).method(method,HttpRequest.BodyPublishers.noBody()).build();
        var response=client.send(request,HttpResponse.BodyHandlers.ofString());
        return new Reply(response.statusCode(),json.readTree(response.body()));
    }
    static String encode(String s) {return URLEncoder.encode(s,StandardCharsets.UTF_8);}
    static Set<String> keys(JsonNode value) {var keys=new HashSet<String>();value.fieldNames().forEachRemaining(keys::add);return keys;}
    static List<Integer> ids(JsonNode value) {var result=new ArrayList<Integer>();value.forEach(item->result.add(item.path("id").intValue()));return result;}
    static String required(String key) {String value=System.getenv(key);assertNotNull(value,key+" required");return value;}
    JdbcTemplate control()throws Exception {
        var values=new HashMap<String,String>();
        for(String key:List.of("PUBBURI_JPA_REHEARSAL","PUBBURI_PG_URL","PUBBURI_PG_DB_NAME","PUBBURI_PG_USER","PUBBURI_PG_PASSWORD","PUBBURI_PG_RUN_TOKEN","PUBBURI_PG_CA","PUBBURI_JPA_SOURCE_ID"))values.put(key,required(key));
        var settings=RehearsalSettings.from(values);var source=new DriverManagerDataSource();source.setUrl(settings.url());source.setConnectionProperties(settings.properties());
        source.setUsername(required("PUBBURI_JPA_CONTROL_USER"));source.setPassword(required("PUBBURI_JPA_CONTROL_PASSWORD"));var sql=new JdbcTemplate(source);
        assertEquals(1,sql.queryForObject("SELECT count(*) FROM public.pubburi_pg_migration_guard WHERE token=?",Integer.class,settings.token()));return sql;
    }

    class Fixture implements AutoCloseable {
        final JdbcTemplate sql=control();
        final String prefix="ci_jpa_read_"+UUID.randomUUID().toString().replace("-","");
        final List<Integer> owned=new ArrayList<>();
        int a,b,c,d,e,f;
        Fixture()throws Exception {
            try {
                a=add(" Café","증류주",1000,5.1f,20);b=add(" Cafe","증류주",2000,5.55f,20);
                c=add(" 한글","발효주",3000,5.555f,10);d=add(" 100%","증류주",0,0,0);
                e=add(" under_score","증류주",1500,5.1f,0);f=add(" slash\\tail","증류주",1500,5.1f,0);
                feature(a,true,true,750,List.of("fruity","dry"),List.of("floral"));
                feature(b,true,true,null,List.of("dry"),List.of("floral"));
                // Old tags in an unknown group must not become known by merely existing.
                feature(c,false,false,null,List.of("dry","fruity"),List.of("floral"));
                feature(d,true,true,null,List.of(),List.of());
                feature(f,true,false,null,List.of("dry,fruity"),List.of());
            } catch(Throwable error) {close();throw error;}
        }
        int add(String suffix,String type,int price,float abv,int count) {
            int id=sql.queryForObject("INSERT INTO pubburi.t_product(name,type,price,img,abv,order_count) VALUES(?,?,?,'authored.png',?,?) RETURNING id",
                Integer.class,prefix+suffix,type,price,abv,count);owned.add(id);return id;
        }
        void feature(int id,boolean taste,boolean aroma,Integer volume,List<String> tasteTags,List<String> aromaTags) {
            sql.update("INSERT INTO pubburi.t_product_feature(product_id,volume_ml,taste_known,aroma_known,source_name) VALUES(?,?,?,?,?)",id,volume,taste,aroma,"Authored fixture only");
            for(String value:tasteTags)sql.update("INSERT INTO pubburi.t_product_feature_tag(product_id,kind,tag) VALUES(?,'TASTE',?)",id,value);
            for(String value:aromaTags)sql.update("INSERT INTO pubburi.t_product_feature_tag(product_id,kind,tag) VALUES(?,'AROMA',?)",id,value);
        }
        String page(String suffix) {return "/api/products?q="+encode(prefix)+suffix;}
        List<Integer> readIds(String suffix)throws Exception {var reply=get(page(suffix));assertEquals(200,reply.status());return ids(reply.body().path("data").path("items"));}
        @Override public void close() {for(int id:owned)sql.update("DELETE FROM pubburi.t_product WHERE id=? AND name LIKE ?",id,prefix+"%");}
    }

    @Test void actualDtoFiltersFloatStorageDomainAndAllTagsMatchTheSamePageCount()throws Exception {
        try(var f=new Fixture()) {
            var all=get(f.page("&size=100"));assertEquals(200,all.status());var page=all.body().path("data");
            assertEquals(Set.of("items","page","size","total","totalPages","hasNext"),keys(page));assertEquals(6,page.path("total").intValue());
            assertEquals(List.of(f.a,f.b,f.c,f.d,f.e,f.f),ids(page.path("items")));
            assertEquals(Set.of("id","name","type","price","img","abv","orderCount"),keys(page.path("items").get(0)));
            assertEquals(Float.floatToIntBits(5.1f),Float.floatToIntBits((float)get("/api/products/"+f.a).body().path("data").path("abv").doubleValue()));
            assertEquals(List.of(f.a,f.b),f.readIds("&tasteTag=dry&aromaTag=floral"));
            assertEquals(List.of(f.a),f.readIds("&tasteTag=fruity&tasteTag=dry&aromaTag=floral"));
            assertEquals(List.of(f.f),f.readIds("&tasteTag=dry%2Cfruity"));
            assertEquals(List.of(f.a,f.e,f.f),f.readIds("&minAbvPercent=5.10&maxAbvPercent=5.10"));
            assertEquals(List.of(f.b),f.readIds("&minAbvPercent=5.55&maxAbvPercent=5.55"));
            assertEquals(List.of(f.b),f.readIds("&maxAbvPercent=5.55&minPriceKrw=2000"));
            assertEquals(List.of(f.d),f.readIds("&minPriceKrw=0&maxPriceKrw=0&minAbvPercent=0&maxAbvPercent=0"));
            assertEquals(List.of(f.a),f.readIds("&minPriceKrw=1000&maxPriceKrw=1000&type="+encode("증류주")));
            var last=get(f.page("&sort=priceAsc&page=3&size=2"));assertEquals(200,last.status());
            assertEquals(List.of(f.b,f.c),ids(last.body().path("data").path("items")));assertEquals(6,last.body().path("data").path("total").intValue());
            assertEquals(3,last.body().path("data").path("totalPages").intValue());assertFalse(last.body().path("data").path("hasNext").booleanValue());
            var none=get(f.page("&tasteTag=absent"));assertEquals(0,none.body().path("data").path("total").intValue());assertTrue(none.body().path("data").path("items").isEmpty());
        }
    }

    @Test void existingSortsPatternEscapesCollationAndPageNormalizationRemainExplicit()throws Exception {
        try(var f=new Fixture()) {
            assertEquals(List.of(f.d,f.a,f.e,f.f,f.b,f.c),f.readIds("&sort=priceAsc"));
            assertEquals(List.of(f.c,f.b,f.e,f.f,f.a,f.d),f.readIds("&sort=priceDesc"));
            assertEquals(List.of(f.a,f.b,f.c,f.d,f.e,f.f),f.readIds("&sort=unknown"));
            List<Integer> names=f.sql.query("SELECT id FROM pubburi.t_product WHERE name LIKE ? ORDER BY name,id",(r,n)->r.getInt(1),"%"+f.prefix+"%");
            assertEquals(names,f.readIds("&sort=name"));
            var caseAndAccent=get("/api/products?q="+encode(f.prefix+"%CAFÉ")+"&sort=name");assertEquals(List.of(f.a,f.b),ids(caseAndAccent.body().path("data").path("items")));
            for(var example:Map.of(f.prefix+"%100\\%",f.d,f.prefix+"%under\\_score",f.e,f.prefix+"%slash\\\\tail",f.f,f.prefix+"%한글",f.c).entrySet()) {
                var reply=get("/api/products?q="+encode(example.getKey()));assertEquals(200,reply.status());assertEquals(List.of(example.getValue()),ids(reply.body().path("data").path("items")));
            }
            assertEquals(ids(get(f.page("&page=1&size=12")).body().path("data").path("items")),ids(get(f.page("&page=0&size=0")).body().path("data").path("items")));
            assertEquals(100,get(f.page("&size=1000")).body().path("data").path("size").intValue());
            for(String query:List.of("&page=2147483647&size=100","&page=178956972&size=12","&minPriceKrw=-1","&maxPriceKrw=2147483648","&minAbvPercent=5.555","&tasteTag=Dry&tasteTag=dry","&tasteTag="))
                assertEquals(400,get(f.page(query)).status(),query);
            var max=get(f.page("&page=2147483647&size=1"));assertEquals(200,max.status());assertEquals(6,max.body().path("data").path("total").intValue());assertTrue(max.body().path("data").path("items").isEmpty());
            var popular=get("/api/products/popular");assertEquals(200,popular.status());
            List<Integer> expected=f.sql.query("SELECT id FROM pubburi.t_product ORDER BY order_count DESC,id LIMIT 12",(r,n)->r.getInt(1));assertEquals(expected,ids(popular.body().path("data")));
        }
    }

    @Test void featureUnknownAndReviewedEmptyAreDifferentAndUnregisteredWritesStayUnavailable()throws Exception {
        try(var f=new Fixture()) {
            var known=get("/api/products/"+f.a+"/features");assertEquals(200,known.status());var value=known.body().path("data");
            assertEquals(Set.of("productId","volumeMl","description","tasteTags","aromaTags","sourceName","sourceUrl","sourceNote"),keys(value));
            assertEquals(json.readTree("[\"dry\",\"fruity\"]"),value.path("tasteTags"));assertEquals(750,value.path("volumeMl").intValue());
            for(int id:List.of(f.c,f.e)) {var unknown=get("/api/products/"+id+"/features");assertEquals(200,unknown.status());assertTrue(unknown.body().path("data").path("tasteTags").isNull());assertTrue(unknown.body().path("data").path("aromaTags").isNull());}
            var empty=get("/api/products/"+f.d+"/features");assertTrue(empty.body().path("data").path("tasteTags").isArray());assertTrue(empty.body().path("data").path("tasteTags").isEmpty());
            assertEquals(406,request("GET","/api/products/"+f.a+"/features","text/plain").status());
            assertEquals(404,request("POST","/api/admin/products",null).status());assertEquals(404,request("PUT","/api/admin/products/"+f.a+"/features",null).status());
            f.sql.update("DELETE FROM pubburi.t_product WHERE id=? AND name LIKE ?",f.a,f.prefix+"%");
            for(String path:List.of("/api/products/"+f.a,"/api/products/"+f.a+"/features","/api/products/0","/api/products/0/features"))assertEquals(404,get(path).status());
            for(String statement:List.of("UPDATE pubburi.t_product SET price=price WHERE FALSE","DELETE FROM pubburi.t_product WHERE FALSE",
                    "INSERT INTO pubburi.t_product(name,type,price,img,abv,order_count) SELECT 'ci_jpa_forbidden','test',1,'test',0,0 WHERE FALSE",
                    "UPDATE pubburi.t_product_feature SET taste_known=taste_known WHERE FALSE","DELETE FROM pubburi.t_product_feature_tag WHERE FALSE")) {
                var denied=assertThrows(org.springframework.dao.DataAccessException.class,()->runtime.execute(statement));assertEquals("42501",((SQLException)denied.getMostSpecificCause()).getSQLState());
            }
            assertFalse(runtime.queryForObject("SELECT has_sequence_privilege('pubburi.t_product_id_seq','USAGE,SELECT,UPDATE')",Boolean.class));
        }
    }

    @Test void concurrentInsertCannotMakePageItemsAndCountUseDifferentSnapshots()throws Exception {
        try(var f=new Fixture();var pause=gate.arm("count")) {
            var worker=Executors.newSingleThreadExecutor();
            try {
                Future<Reply> reading=worker.submit(()->get(f.page("&size=100")));
                assertTrue(pause.entered.await(4,TimeUnit.SECONDS));int next=f.add(" concurrent","증류주",1,1,1);pause.release.countDown();
                var result=reading.get(5,TimeUnit.SECONDS);assertEquals(200,result.status());assertEquals(6,result.body().path("data").path("total").intValue());assertFalse(ids(result.body().path("data").path("items")).contains(next));
                var after=get(f.page("&size=100"));assertEquals(7,after.body().path("data").path("total").intValue());assertTrue(ids(after.body().path("data").path("items")).contains(next));
            } finally {pause.close();worker.shutdownNow();assertTrue(worker.awaitTermination(5,TimeUnit.SECONDS));}
        }
    }

    @Test void concurrentReplacementCannotMixFeatureMetadataAndNewTags()throws Exception {
        try(var f=new Fixture();var pause=gate.arm("tags")) {
            var worker=Executors.newSingleThreadExecutor();
            try {
                Future<Reply> reading=worker.submit(()->get("/api/products/"+f.a+"/features"));assertTrue(pause.entered.await(4,TimeUnit.SECONDS));
                f.sql.execute((ConnectionCallback<Void>)connection->{
                    connection.setAutoCommit(false);
                    try {
                        try(var update=connection.prepareStatement("UPDATE pubburi.t_product_feature SET volume_ml=1000 WHERE product_id=?")) {update.setInt(1,f.a);assertEquals(1,update.executeUpdate());}
                        try(var delete=connection.prepareStatement("DELETE FROM pubburi.t_product_feature_tag WHERE product_id=? AND kind='TASTE'")) {delete.setInt(1,f.a);delete.executeUpdate();}
                        try(var insert=connection.prepareStatement("INSERT INTO pubburi.t_product_feature_tag(product_id,kind,tag) VALUES(?,'TASTE','new')")) {insert.setInt(1,f.a);insert.executeUpdate();}
                        connection.commit();
                    } catch(Throwable error) {connection.rollback();throw error;}
                    return null;
                });
                pause.release.countDown();var result=reading.get(5,TimeUnit.SECONDS);assertEquals(200,result.status());assertEquals(750,result.body().path("data").path("volumeMl").intValue());assertEquals(json.readTree("[\"dry\",\"fruity\"]"),result.body().path("data").path("tasteTags"));
                var after=get("/api/products/"+f.a+"/features");assertEquals(1000,after.body().path("data").path("volumeMl").intValue());assertEquals(json.readTree("[\"new\"]"),after.body().path("data").path("tasteTags"));
            } finally {pause.close();worker.shutdownNow();assertTrue(worker.awaitTermination(5,TimeUnit.SECONDS));}
        }
    }

    @Test void runtimeOpenApiRetainsProductAndFeatureSchemasAndRepeatedTagQuery()throws Exception {
        var response=get("/v3/api-docs");assertEquals(200,response.status());var spec=response.body();var paths=spec.path("paths");
        for(String path:List.of("/api/products","/api/products/popular","/api/products/{id}","/api/products/{id}/features"))assertEquals(Set.of("get"),keys(paths.path(path)));
        assertEquals(Set.of("id","name","type","price","img","abv","orderCount"),keys(spec.path("components").path("schemas").path("ProductResponse").path("properties")));
        assertEquals(Set.of("productId","volumeMl","description","tasteTags","aromaTags","sourceName","sourceUrl","sourceNote"),keys(spec.path("components").path("schemas").path("ProductFeaturesResponse").path("properties")));
        for(String name:List.of("tasteTag","aromaTag")) {
            JsonNode parameter=null;for(JsonNode candidate:paths.path("/api/products").path("get").path("parameters"))if(candidate.path("name").asText().equals(name))parameter=candidate;
            assertNotNull(parameter);assertEquals("form",parameter.path("style").asText());assertTrue(parameter.path("explode").asBoolean());assertEquals("array",parameter.path("schema").path("type").asText());
        }
    }

    static final class Pause implements AutoCloseable {
        final String kind;final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        Pause(String kind) {this.kind=kind;}
        @Override public void close() {release.countDown();}
    }
    static final class QueryGate implements StatementInspector {
        final AtomicReference<Pause> armed=new AtomicReference<>();
        Pause arm(String kind) {var pause=new Pause(kind);assertTrue(armed.compareAndSet(null,pause));return pause;}
        @Override public String inspect(String sql) {
            var pause=armed.get();String lower=sql.toLowerCase(Locale.ROOT);
            boolean match=pause!=null && (pause.kind.equals("count")?lower.startsWith("select count(") && lower.contains("pubburi.t_product "):lower.contains(" from pubburi.t_product_feature_tag "));
            if(match && armed.compareAndSet(pause,null)) {
                pause.entered.countDown();
                try {if(!pause.release.await(6,TimeUnit.SECONDS))throw new IllegalStateException("owned read gate deadline");}
                catch(InterruptedException error) {Thread.currentThread().interrupt();throw new IllegalStateException("owned read gate interrupted");}
            }
            return sql;
        }
    }
    @TestConfiguration(proxyBeanMethods=false)
    static class Probes {
        @Bean QueryGate queryGate() {return new QueryGate();}
        @Bean HibernatePropertiesCustomizer readProbe(QueryGate probe) {return settings->settings.put("hibernate.session_factory.statement_inspector",probe);}
    }
}
