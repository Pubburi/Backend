package com.pubburi.pub.search;

import static org.assertj.core.api.Assertions.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import com.fasterxml.jackson.databind.*;
import com.pubburi.pub.controller.api.*;
import com.sun.net.httpserver.*;
import org.junit.jupiter.api.Test;

class ProductSearchHttpClientTests {
    static final String SOURCE="76b33720-8b20-4ed4-809e-3ea301fab26f",INDEX="pubburi-products-v1-test";
    static final String HITS="""
        {"timed_out":false,"_shards":{"total":1,"successful":1,"failed":0},"hits":{"total":{"value":2,"relation":"eq"},"hits":[{"_id":"2","_index":"pubburi-products-v1-test"},{"_id":"5","_index":"pubburi-products-v1-test"}]}}
        """;
    @Test void queryIncludesEveryHardKnownTagAtomAndDeterministicSortWithoutQueryStringSyntax()throws Exception{
        try(var server=new Server()){
            var filter=ProductSearchFilters.fromQuery("0","12000","0","5.10",new String[]{"a,b","line\nx"},new String[]{"floral"});
            var selected=server.client().search(SOURCE,"맥주","a*?b","priceAsc",filter,PageCriteria.of(1,12,12));assertThat(selected).contains(new ProductSearchGateway.Selection(List.of(2,5),2));
            JsonNode body=server.last.get();assertThat(body.path("_source").booleanValue()).isFalse();assertThat(body.path("track_total_hits").booleanValue()).isTrue();assertThat(body.path("from").intValue()).isZero();
            String wire=body.toString();for(String required:List.of("deleted","category","price_krw","abv_percent","taste_known","taste_tags","aroma_known","aroma_tags","a,b","line\\nx","minimum_should_match"))assertThat(wire).contains(required);
            assertThat(body.at("/sort/0/price_krw").textValue()).isEqualTo("asc");assertThat(body.at("/sort/1/product_id").textValue()).isEqualTo("asc");
            assertThat(wire).doesNotContain("query_string");assertThat(server.calls).hasValue(3);assertThat(server.authorization.get()).isNull();assertThat(server.cookie.get()).isNull();
        }
    }
    @Test void sourceGenerationPartialResultsTypesIdsAndMediaFailuresNeverBecomeEngineSuccess()throws Exception{
        try(var server=new Server()){
            for(String bad:List.of("","null","[]",HITS.replace("\"timed_out\":false","\"timed_out\":true"),HITS.replace("\"failed\":0","\"failed\":1"),HITS.replace("\"relation\":\"eq\"","\"relation\":\"gte\""),HITS.replace("\"_id\":\"5\"","\"_id\":\"2\""),HITS.replace("\"_id\":\"2\"","\"_id\":\"0\""),HITS.replace("\"value\":2","\"value\":2.0"),HITS.replace("\"_id\":\"2\"","\"_source\":{},\"_id\":\"2\""),HITS+" {}",HITS.replace("\"timed_out\":false","\"timed_out\":false,\"timed_out\":false"))){server.response.set(bad);assertThat(server.call()).isEmpty();}
            server.response.set(HITS);
            for(String mode:List.of("status","redirect","media","source","generation")){server.mode.set(mode);int before=server.calls.get();assertThat(server.call()).isEmpty();assertThat(server.calls.get()-before).isLessThanOrEqualTo(3);}
        }
    }
    @Test void unsupportedLegacyLikeDeepPagesAndInvalidSourceNeverMakeRemoteRequests()throws Exception{
        try(var server=new Server()){
            for(String q:List.of("%","_","a\\b","x".repeat(101)))assertThat(server.client().search(SOURCE,null,q,null,ProductSearchFilters.none(),PageCriteria.of(1,12,12))).isEmpty();
            assertThat(server.client().search(SOURCE,null,null,null,ProductSearchFilters.none(),PageCriteria.of(101,100,12))).isEmpty();
            assertThat(server.client().search("wrong-source",null,null,null,ProductSearchFilters.none(),PageCriteria.of(1,12,12))).isEmpty();assertThat(server.calls).hasValue(0);
        }
        for(String origin:List.of("http://example.com:9200","http://user:password@127.0.0.1:9200","http://127.0.0.1:9200/private","http://127.0.0.1:9200?q=x"))assertThatThrownBy(()->new ProductSearchHttpClient(URI.create(origin),INDEX,Duration.ofMillis(100),Duration.ofMillis(700),new ObjectMapper())).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void cumulativeBodyCapAndUnfinishedBodyAreBoundedAcrossTheEntireExchange()throws Exception{
        try(var server=new Server()){
            for(int length:List.of(131072,131073)){server.response.set(HITS+" ".repeat(length-HITS.getBytes(StandardCharsets.UTF_8).length));assertThat(server.call().isPresent()).isEqualTo(length==131072);}
            server.response.set(HITS);server.mode.set("slowBody");var executor=Executors.newSingleThreadExecutor();
            try{var result=executor.submit(server::call);assertThat(server.entered.await(2,TimeUnit.SECONDS)).isTrue();assertThat(result.get(2,TimeUnit.SECONDS)).isEmpty();assertThat(server.release.getCount()).isEqualTo(1);}
            finally{server.release.countDown();executor.shutdownNow();assertThat(executor.awaitTermination(2,TimeUnit.SECONDS)).isTrue();}
        }
    }
    static final class Server implements AutoCloseable{
        final HttpServer http;final ExecutorService executor=Executors.newCachedThreadPool();final AtomicReference<String> response=new AtomicReference<>(HITS),mode=new AtomicReference<>("normal"),authorization=new AtomicReference<>(),cookie=new AtomicReference<>();final AtomicReference<JsonNode> last=new AtomicReference<>();final AtomicInteger calls=new AtomicInteger(),metadata=new AtomicInteger();final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        Server()throws Exception{http=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);http.setExecutor(executor);http.createContext("/",exchange->{try{
            calls.incrementAndGet();authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));cookie.set(exchange.getRequestHeaders().getFirst("Cookie"));String body;
            if(exchange.getRequestMethod().equals("GET")){int n=metadata.incrementAndGet();String source=mode.get().equals("source")?"00000000-0000-0000-0000-000000000000":SOURCE;String generation=mode.get().equals("generation") && n%2==0?"changed":"physical-identity";body="{\""+INDEX+"\":{\"settings\":{\"index\":{\"uuid\":\""+generation+"\"}},\"mappings\":{\"_meta\":{\"owner\":\"pubburi\",\"schema\":\"products-v1\",\"data_kind\":\"backend_product_events\",\"source_id\":\""+source+"\"}}}}";}
            else{last.set(new ObjectMapper().readTree(exchange.getRequestBody()));body=response.get();}
            if(mode.get().equals("slowBody") && exchange.getRequestMethod().equals("POST")){exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,0);try(var output=exchange.getResponseBody()){output.write("{".getBytes(StandardCharsets.UTF_8));output.flush();entered.countDown();release.await(4,TimeUnit.SECONDS);}return;}
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type",mode.get().equals("media")?"text/html":"application/json");exchange.getResponseHeaders().set("Location","/must-not-follow");exchange.sendResponseHeaders(mode.get().equals("status")?503:mode.get().equals("redirect")?302:200,bytes.length);try(var output=exchange.getResponseBody()){output.write(bytes);}
        }catch(Exception error){exchange.close();}});http.start();}
        ProductSearchHttpClient client(){return new ProductSearchHttpClient(URI.create("http://127.0.0.1:"+http.getAddress().getPort()),INDEX,Duration.ofMillis(100),Duration.ofMillis(700),new ObjectMapper());}
        Optional<ProductSearchGateway.Selection> call(){metadata.set(0);return client().search(SOURCE,null,null,null,ProductSearchFilters.none(),PageCriteria.of(1,12,12));}
        @Override public void close()throws Exception{release.countDown();http.stop(0);executor.shutdownNow();assertThat(executor.awaitTermination(3,TimeUnit.SECONDS)).isTrue();}
    }
}
