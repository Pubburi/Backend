package com.pubburi.pub.recommendation;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pubburi.pub.controller.response.UserPreferencesResponse;

/** Bounded transport only. The owning service validates the response against its snapshot. */
public final class SearchPhraseHttpClient implements SearchPhraseGateway {
    public static final int MAX_RESPONSE_BYTES = 131072;
    private final HttpClient client;
    private final URI endpoint;
    private final Duration deadline;
    private final ObjectMapper mapper;
    public SearchPhraseHttpClient(URI origin, Duration connectTimeout, Duration deadline, ObjectMapper mapper) {
        if (!("http".equals(origin.getScheme()) || "https".equals(origin.getScheme())) || origin.getHost() == null
                || origin.getUserInfo() != null || origin.getQuery() != null || origin.getFragment() != null
                || !(origin.getPath().isEmpty() || "/".equals(origin.getPath())))
            throw new IllegalArgumentException("Recommendation base URL must be an operator-configured HTTP origin");
        if (connectTimeout.isNegative() || connectTimeout.isZero() || deadline.isNegative() || deadline.isZero())
            throw new IllegalArgumentException("Recommendation timeouts must be positive");
        this.client = HttpClient.newBuilder().connectTimeout(connectTimeout).followRedirects(HttpClient.Redirect.NEVER).build();
        this.endpoint = origin.resolve("/v1/recommendations/search-phrases");
        this.deadline = deadline;
        this.mapper = mapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.mapper.getFactory().setStreamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(16)
                .maxStringLength(1024).maxNameLength(128).maxDocumentLength(MAX_RESPONSE_BYTES).build());
    }
    @Override public Optional<JsonNode> suggest(UserPreferencesResponse preferences, List<String> fields, int limit) {
        CompletableFuture<HttpResponse<byte[]>> exchange = null;
        AtomicReference<BaselineHttpClient.LimitedBody> active = new AtomicReference<>();
        AtomicBoolean cancelled = new AtomicBoolean();
        try {
            byte[] payload = mapper.writeValueAsBytes(Map.of("preferences", preferences, "search_opt_in_fields", fields, "limit", limit));
            HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(deadline).header("Content-Type", "application/json")
                    .header("Accept", "application/json").POST(HttpRequest.BodyPublishers.ofByteArray(payload)).build();
            exchange = client.sendAsync(request, info -> {
                var body = new BaselineHttpClient.LimitedBody(MAX_RESPONSE_BYTES); active.set(body);
                boolean accepted = info.statusCode() == 200 && "application/json".equalsIgnoreCase(
                        info.headers().firstValue("Content-Type").orElse("").split(";", 2)[0].strip());
                try { accepted &= info.headers().firstValueAsLong("Content-Length").orElse(0) <= MAX_RESPONSE_BYTES; }
                catch (NumberFormatException invalid) { accepted = false; }
                if (!accepted || cancelled.get()) body.abort();
                return body;
            });
            return Optional.ofNullable(mapper.readTree(exchange.get(deadline.toMillis(), TimeUnit.MILLISECONDS).body()));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); return Optional.empty();
        } catch (java.io.IOException | ExecutionException | TimeoutException | IllegalArgumentException unavailable) {
            return Optional.empty();
        } finally {
            cancelled.set(true);
            if (exchange != null && !exchange.isDone()) exchange.cancel(true);
            var body = active.get(); if (body != null) body.abort();
        }
    }
}
