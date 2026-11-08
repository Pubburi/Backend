package com.pubburi.pub.recommendation;

import static org.assertj.core.api.Assertions.*;
import com.sun.net.httpserver.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

class ContentHttpClientTests {
    @Test void contextUsesOnlyTheFixedProviderRouteUniqueServerReferencesAndLiteralHardBounds()throws Exception{
        var sent=new AtomicReference<com.fasterxml.jackson.databind.JsonNode>();var failure=new AtomicReference<Throwable>();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{try{
            assertThat(exchange.getRequestURI().toString()).isEqualTo("/v1/recommendations/context");assertThat(exchange.getRequestHeaders().getFirst("Cookie")).isNull();assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isNull();sent.set(ContentFixtures.JSON.readTree(exchange.getRequestBody().readAllBytes()));
            exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,2);exchange.getResponseBody().write("{}".getBytes(StandardCharsets.UTF_8));
        }catch(Throwable error){failure.set(error);}finally{exchange.close();}});server.start();
        try{
            var client=new ContentHttpClient(URI.create("http://127.0.0.1:"+server.getAddress().getPort()),Duration.ofMillis(200),Duration.ofSeconds(1),ContentFixtures.JSON);
            var bounds=new com.pubburi.pub.controller.response.UserPreferencesResponse.PreferenceHardResponse(0,2000,new java.math.BigDecimal("0.00"),new java.math.BigDecimal("40.00"));
            assertThat(client.context(ContentFixtures.catalog().catalog(),java.util.List.of(81),bounds,java.util.List.of(82),4)).isPresent();
            var fields=new java.util.HashSet<String>();sent.get().fieldNames().forEachRemaining(fields::add);assertThat(fields).containsExactlyInAnyOrder("catalog","reference_product_ids","constraints","limit","min_relevance_score","include_explanations");
            assertThat(sent.get().path("reference_product_ids")).isEqualTo(ContentFixtures.JSON.valueToTree(java.util.List.of(81)));assertThat(sent.get().at("/constraints/min_price_krw").intValue()).isZero();assertThat(sent.get().at("/constraints/max_abv_percent").decimalValue()).isEqualByComparingTo("40");assertThat(sent.get().at("/constraints/exclude_product_ids")).isEqualTo(ContentFixtures.JSON.valueToTree(java.util.List.of(82)));assertThat(failure.get()).isNull();
        }finally{server.stop(0);}
    }
    @Test void fixedRouteOnlyCatalogPreferencesAndNoCredentialsAreSentWithExactRequestResponseBounds()throws Exception{
        var calls=new AtomicInteger();var mode=new AtomicReference<>("normal");var sent=new AtomicReference<com.fasterxml.jackson.databind.JsonNode>();var failure=new AtomicReference<Throwable>();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{try{
            calls.incrementAndGet();assertThat(exchange.getRequestURI().getPath()).isEqualTo("/v1/recommendations/content");assertThat(exchange.getRequestHeaders().getFirst("Cookie")).isNull();assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isNull();
            sent.set(ContentFixtures.JSON.readTree(exchange.getRequestBody().readAllBytes()));String body=switch(mode.get()){case "duplicate"->"{\"x\":1,\"x\":2}";case "trailing"->"{} {}";case "oversized"->"{}"+" ".repeat(ContentHttpClient.MAX_RESPONSE_BYTES);case "boundary"->"{}"+" ".repeat(ContentHttpClient.MAX_RESPONSE_BYTES-2);default->"{}";};
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type",mode.get().equals("media")?"text/plain":"application/json");exchange.getResponseHeaders().set("Location","/must-not-follow");exchange.sendResponseHeaders(mode.get().equals("redirect")?302:mode.get().equals("status")?503:200,bytes.length);exchange.getResponseBody().write(bytes);
        }catch(java.io.IOException cancelled){/* Bounded rejection may close the peer. */}catch(Throwable error){failure.set(error);}finally{exchange.close();}});server.start();
        try{
            var client=new ContentHttpClient(URI.create("http://127.0.0.1:"+server.getAddress().getPort()),Duration.ofMillis(200),Duration.ofSeconds(1),ContentFixtures.JSON);
            assertThat(client.recommend(ContentFixtures.catalog().catalog(),ContentFixtures.preferences(),4,null)).isPresent();
            assertThat(sent.get().size()).isEqualTo(5);assertThat(sent.get().path("include_explanations").booleanValue()).isTrue();assertThat(sent.get().path("constraints").size()).isZero();assertThat(sent.get().has("profile")).isFalse();assertThat(sent.get().has("userId")).isFalse();
            for(String value:new String[]{"duplicate","trailing","oversized","media","redirect","status"}){mode.set(value);int before=calls.get();assertThat(client.recommend(ContentFixtures.catalog().catalog(),ContentFixtures.preferences(),4,null)).isEmpty();assertThat(calls.get()).isEqualTo(before+1);}
            mode.set("boundary");assertThat(client.recommend(ContentFixtures.catalog().catalog(),ContentFixtures.preferences(),4,null)).isPresent();
            int before=calls.get();assertThat(client.recommend(ContentFixtures.JSON.getNodeFactory().textNode("x".repeat(ContentHttpClient.MAX_REQUEST_BYTES)),ContentFixtures.preferences(),4,null)).isEmpty();assertThat(calls.get()).isEqualTo(before);assertThat(failure.get()).isNull();
        }finally{server.stop(0);}
    }
    @Test void unfinishedBodyUsesOneAbsoluteDeadlineAndInvalidOperatorOriginsAreRejected()throws Exception{
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var release=new CountDownLatch(1);var executor=Executors.newSingleThreadExecutor();server.setExecutor(executor);
        server.createContext("/",exchange->{try{exchange.getRequestBody().readAllBytes();exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,0);exchange.getResponseBody().write("{}".getBytes(StandardCharsets.UTF_8));exchange.getResponseBody().flush();release.await(2,TimeUnit.SECONDS);}catch(Exception closed){}finally{exchange.close();}});server.start();
        try{var client=new ContentHttpClient(URI.create("http://127.0.0.1:"+server.getAddress().getPort()),Duration.ofMillis(100),Duration.ofMillis(100),ContentFixtures.JSON);assertThat(client.recommend(ContentFixtures.catalog().catalog(),ContentFixtures.preferences(),4,null)).isEmpty();}
        finally{release.countDown();server.stop(0);executor.shutdownNow();assertThat(executor.awaitTermination(3,TimeUnit.SECONDS)).isTrue();}
        for(String invalid:new String[]{"ftp://127.0.0.1:1","http://user:pass@127.0.0.1:1","http://127.0.0.1:1/route","http://127.0.0.1:1?key=x"})assertThatThrownBy(()->new ContentHttpClient(URI.create(invalid),Duration.ofMillis(1),Duration.ofMillis(1),ContentFixtures.JSON)).isInstanceOf(IllegalArgumentException.class);
    }
}
