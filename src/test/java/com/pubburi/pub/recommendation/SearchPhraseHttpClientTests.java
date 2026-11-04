package com.pubburi.pub.recommendation;

import static org.assertj.core.api.Assertions.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pubburi.pub.controller.response.UserPreferencesResponse;
import com.sun.net.httpserver.*;
import org.junit.jupiter.api.Test;

class SearchPhraseHttpClientTests {
    @Test void configuredOriginSendsOnlySnapshotChoicesAndLimitWithoutCredentials() throws Exception {
        try (var server = new Server()) {
            server.handler.set(exchange -> {
                assertThat(exchange.getRequestURI().toString()).isEqualTo("/v1/recommendations/search-phrases");
                assertThat(exchange.getRequestMethod()).isEqualTo("POST");
                assertThat(exchange.getRequestHeaders().getFirst("Cookie")).isNull(); assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isNull();
                String raw = new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);
                var body = new ObjectMapper().readTree(raw); assertThat(body.size()).isEqualTo(3);
                assertThat(raw).contains("\"preferences\":"+new ObjectMapper().writeValueAsString(UserPreferencesResponse.from(SearchPhraseFixtures.preferences())));
                assertThat(body.path("search_opt_in_fields").get(0).textValue()).isEqualTo("tasteTags");
                assertThat(body.path("limit").intValue()).isEqualTo(5); server.reply(exchange,200,"application/json",SearchPhraseFixtures.selectedTaste().toString());
            });
            assertThat(server.call()).contains(SearchPhraseFixtures.selectedTaste());
        }
        for (String bad : List.of("http://user:pass@127.0.0.1", "http://127.0.0.1/private", "http://127.0.0.1?q=x", "file:///tmp/local"))
            assertThatThrownBy(() -> new SearchPhraseHttpClient(URI.create(bad),Duration.ofMillis(100),Duration.ofMillis(700),new ObjectMapper())).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void redirectsMediaDuplicateJsonAndBothBodySizeBoundariesFailClosed() throws Exception {
        try (var server = new Server()) {
            String valid = SearchPhraseFixtures.allProducts().toString();
            for (String body : List.of("not json", valid+" {}",valid.replace("\"algorithm\":","\"algorithm\":\"duplicate\",\"algorithm\":"))) {
                server.handler.set(exchange -> server.reply(exchange,200,"application/json",body)); assertThat(server.call()).isEmpty();
            }
            for (int status : List.of(302,422,503)) {
                int before=server.calls.get(); server.handler.set(exchange -> { exchange.getResponseHeaders().set("Location","/redirect");server.reply(exchange,status,"application/json",valid); });
                assertThat(server.call()).isEmpty(); assertThat(server.calls).hasValue(before+1);
            }
            server.handler.set(exchange -> server.reply(exchange,200,"text/html",valid)); assertThat(server.call()).isEmpty();
            for (int length : List.of(131072,131073)) {
                String padded=valid+" ".repeat(length-valid.getBytes(StandardCharsets.UTF_8).length);
                server.handler.set(exchange -> server.reply(exchange,200,"application/json",padded));
                assertThat(server.call().isPresent()).isEqualTo(length==131072);
            }
        }
    }
    @Test void incompleteBodyCannotKeepTheCallerOpenPastItsWholeBodyDeadline() throws Exception {
        try (var server = new Server()) {
            var entered=new CountDownLatch(1); var release=new CountDownLatch(1); var caller=Executors.newSingleThreadExecutor();
            server.handler.set(exchange -> { exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,0);
                try(var output=exchange.getResponseBody()) {output.write("{".getBytes(StandardCharsets.UTF_8));output.flush();entered.countDown();release.await(4,TimeUnit.SECONDS);}
            });
            try { var result=caller.submit(server::call);assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();assertThat(result.get(2,TimeUnit.SECONDS)).isEmpty();assertThat(release.getCount()).isEqualTo(1); }
            finally {release.countDown();caller.shutdownNow();assertThat(caller.awaitTermination(2,TimeUnit.SECONDS)).isTrue();}
        }
    }
    @FunctionalInterface interface Handler {void handle(HttpExchange exchange) throws Exception;}
    static class Server implements AutoCloseable {
        final HttpServer server; final ExecutorService executor=Executors.newCachedThreadPool(); final AtomicReference<Handler> handler=new AtomicReference<>();final AtomicInteger calls=new AtomicInteger();
        Server() throws Exception {server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setExecutor(executor);server.createContext("/",exchange -> {try {calls.incrementAndGet();handler.get().handle(exchange);} catch(Exception error) {exchange.close();}});server.start();}
        java.util.Optional<com.fasterxml.jackson.databind.JsonNode> call() {return new SearchPhraseHttpClient(URI.create("http://127.0.0.1:"+server.getAddress().getPort()),Duration.ofMillis(100),Duration.ofMillis(700),new ObjectMapper()).suggest(UserPreferencesResponse.from(SearchPhraseFixtures.preferences()),List.of("tasteTags"),5);}
        void reply(HttpExchange exchange,int status,String media,String body) throws Exception {byte[] bytes=body.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type",media);exchange.sendResponseHeaders(status,bytes.length);try(var output=exchange.getResponseBody()){output.write(bytes);}}
        @Override public void close() throws Exception {server.stop(0);executor.shutdownNow();assertThat(executor.awaitTermination(3,TimeUnit.SECONDS)).isTrue();}
    }
}
