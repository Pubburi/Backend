package com.pubburi.pub.recommendation;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.pubburi.pub.controller.response.UserPreferencesResponse;
import com.pubburi.pub.controller.response.ContentRecommendationResponse.ActivityEvidence;

/** Fixed B1 routes and bounded transport. Does not fit, score, retry or forward credentials. */
public final class ContentHttpClient implements ContentGateway, SimilarProductGateway, ContextGateway {
    public static final int MAX_REQUEST_BYTES=524288,MAX_RESPONSE_BYTES=262144;
    private final HttpClient client;private final URI endpoint, similarEndpoint, feedbackEndpoint,contextEndpoint,explorationEndpoint;private final Duration deadline;private final ObjectMapper mapper;
    public ContentHttpClient(URI origin,Duration connectTimeout,Duration deadline,ObjectMapper mapper){
        if(!Set.of("http","https").contains(origin.getScheme()) || origin.getHost()==null || origin.getUserInfo()!=null || origin.getQuery()!=null || origin.getFragment()!=null || !(origin.getPath().isEmpty() || origin.getPath().equals("/")) || connectTimeout.isNegative() || connectTimeout.isZero() || deadline.isNegative() || deadline.isZero())throw new IllegalArgumentException("Recommendation origin and positive timeouts are required");
        client=HttpClient.newBuilder().connectTimeout(connectTimeout).followRedirects(HttpClient.Redirect.NEVER).build();endpoint=origin.resolve("/v1/recommendations/content");similarEndpoint=origin.resolve("/v1/recommendations/similar");feedbackEndpoint=origin.resolve("/v1/recommendations/feedback");contextEndpoint=origin.resolve("/v1/recommendations/context");this.deadline=deadline;
        explorationEndpoint = origin.resolve("/v1/recommendations/exploration");
        this.mapper=mapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.mapper.getFactory().setStreamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(20).maxStringLength(5000).maxNameLength(128).maxDocumentLength(MAX_RESPONSE_BYTES).build());
    }
    @Override public Optional<JsonNode> recommend(JsonNode catalog,UserPreferencesResponse preferences,int limit,ActivityEvidence activity){
        return post(endpoint, recommendationFields(catalog, preferences, limit, activity));
    }

    @Override public Optional<JsonNode> explore(JsonNode catalog, UserPreferencesResponse preferences, int limit,
            List<Integer> excluded) {
        var fields = recommendationFields(catalog, preferences, limit, null);
        fields.put("constraints", Map.of("exclude_product_ids", excluded));
        return post(explorationEndpoint, fields);
    }

    @Override public Optional<JsonNode> feedback(JsonNode catalog, UserPreferencesResponse preferences, int limit,
            ActivityEvidence activity, FeedbackSignals signals) {
        if (signals.isEmpty() || !signals.withinProviderLimit()) return Optional.empty();
        var fields = recommendationFields(catalog, preferences, limit, activity);
        fields.put("feedback", Map.of("positive_product_ids", signals.matched(),
            "not_interested_product_ids", signals.notInterested()));
        return post(feedbackEndpoint, fields);
    }

    private Map<String, Object> recommendationFields(JsonNode catalog, UserPreferencesResponse preferences,
            int limit, ActivityEvidence activity) {
        var fields = new LinkedHashMap<String, Object>();
        fields.put("catalog", catalog);
        fields.put("preferences", preferences);
        fields.put("constraints", Map.of());
        fields.put("limit", limit);
        fields.put("include_explanations", true);
        if (activity != null) {
            fields.put("activity_categories", Map.of("favorite_categories", activity.favoriteCategories(),
                "highly_rated_categories", activity.highlyRatedCategories()));
        }
        return fields;
    }

    @Override public Optional<JsonNode> similar(JsonNode catalog, int referenceId, int limit) {
        return post(similarEndpoint, Map.of("catalog", catalog, "reference_product_id", referenceId, "limit", limit));
    }
    @Override public Optional<JsonNode> similarExplained(JsonNode catalog,int referenceId,int limit){
        return post(similarEndpoint,Map.of("catalog",catalog,"reference_product_id",referenceId,"limit",limit,"include_explanations",true));
    }

    @Override public Optional<JsonNode> context(JsonNode catalog,List<Integer> references,
            UserPreferencesResponse.PreferenceHardResponse hard,List<Integer> excluded,int limit) {
        var constraints=new LinkedHashMap<String,Object>();
        constraints.put("exclude_product_ids",excluded);
        if(hard.minPriceKrw()!=null)constraints.put("min_price_krw",hard.minPriceKrw());
        if(hard.maxPriceKrw()!=null)constraints.put("max_price_krw",hard.maxPriceKrw());
        if(hard.minAbvPercent()!=null)constraints.put("min_abv_percent",hard.minAbvPercent());
        if(hard.maxAbvPercent()!=null)constraints.put("max_abv_percent",hard.maxAbvPercent());
        return post(contextEndpoint,Map.of("catalog",catalog,"reference_product_ids",references,
            "constraints",constraints,"limit",limit,"min_relevance_score",0,"include_explanations",true));
    }

    private Optional<JsonNode> post(URI endpoint, Map<String, Object> fields) {
        CompletableFuture<HttpResponse<byte[]>> future=null;var active=new AtomicReference<BaselineHttpClient.LimitedBody>();var cancelled=new AtomicBoolean();
        try{
            long end=System.nanoTime()+deadline.toNanos();
            byte[] payload = mapper.writeValueAsBytes(fields);
            if(payload.length>MAX_REQUEST_BYTES)return Optional.empty();long remaining=end-System.nanoTime();if(remaining<=0)return Optional.empty();
            var request=HttpRequest.newBuilder(endpoint).timeout(Duration.ofNanos(remaining)).header("Accept","application/json").header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofByteArray(payload)).build();
            future=client.sendAsync(request,info->{var body=new BaselineHttpClient.LimitedBody(MAX_RESPONSE_BYTES);active.set(body);
                boolean accepted=info.statusCode()==200 && "application/json".equalsIgnoreCase(info.headers().firstValue("Content-Type").orElse("").split(";",2)[0].strip());
                try{accepted &= info.headers().firstValueAsLong("Content-Length").orElse(0)<=MAX_RESPONSE_BYTES;}catch(NumberFormatException invalid){accepted=false;}
                if(!accepted || cancelled.get())body.abort();return body;});
            remaining=end-System.nanoTime();if(remaining<=0)throw new TimeoutException();return Optional.ofNullable(mapper.readTree(future.get(remaining,TimeUnit.NANOSECONDS).body()));
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();return Optional.empty();}
        catch(java.io.IOException|ExecutionException|TimeoutException|IllegalArgumentException invalid){return Optional.empty();}
        finally{cancelled.set(true);if(future!=null && !future.isDone())future.cancel(true);var body=active.get();if(body!=null)body.abort();}
    }
}
