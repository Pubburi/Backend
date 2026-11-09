package com.pubburi.pub.search;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.pubburi.pub.controller.api.PageCriteria;
import com.pubburi.pub.controller.api.ProductSearchFilters;
import com.pubburi.pub.model.dao.ProductSearchIdentityDao.BoundTarget;

/** Read-only, bounded local engine consumer. Never returns engine source documents. */
public final class ProductSearchHttpClient implements ProductSearchGateway {
    public static final int MAX_RESPONSE_BYTES=131072;
    public static final String READ_ALIAS = "pubburi-products-v1-read";
    private final URI endpoint;
    private final String index;
    private final Supplier<BoundTarget> aliasBinding;
    private final HttpClient http;
    private final Duration deadline;
    private final ObjectMapper mapper;
    public ProductSearchHttpClient(URI endpoint,String index,Duration connectTimeout,Duration deadline,ObjectMapper mapper) {
        this(endpoint, index, connectTimeout, deadline, mapper, null);
    }

    public ProductSearchHttpClient(URI endpoint, String index, Duration connectTimeout, Duration deadline,
            ObjectMapper mapper, Supplier<BoundTarget> aliasBinding) {
        if(!"http".equals(endpoint.getScheme()) || endpoint.getHost()==null || !Set.of("127.0.0.1","::1","[::1]").contains(endpoint.getHost())
                || endpoint.getUserInfo()!=null || endpoint.getQuery()!=null || endpoint.getFragment()!=null
                || !(endpoint.getPath().isEmpty() || endpoint.getPath().equals("/")) || endpoint.getPort()<1)
            throw new IllegalArgumentException("Search origin must be an operator-configured numeric loopback HTTP origin");
        if (!(aliasBinding == null ? physicalIndex(index) : READ_ALIAS.equals(index))
                || deadline.isNegative() || deadline.isZero() || connectTimeout.isNegative() || connectTimeout.isZero())
            throw new IllegalArgumentException("Search index and positive timeouts are required");
        this.endpoint=endpoint;this.index=index;this.deadline=deadline;this.aliasBinding=aliasBinding;
        this.http=HttpClient.newBuilder().connectTimeout(connectTimeout).followRedirects(HttpClient.Redirect.NEVER).build();
        this.mapper=mapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.mapper.getFactory().setStreamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(20).maxNameLength(128).maxStringLength(2048).maxDocumentLength(MAX_RESPONSE_BYTES).build());
    }
    @Override public boolean enabled(){return true;}
    @Override public Optional<Selection> search(String sourceId,String type,String q,String sort,ProductSearchFilters filters,PageCriteria page) {
        long offset=((long)page.page()-1)*page.size();
        // Existing SQL LIKE '%'/'_'/'\\' and arbitrary query lengths keep their old DB meaning.
        if(offset<0 || offset+page.size()>10000 || q!=null && (q.length()>100 || q.indexOf('%')>=0 || q.indexOf('_')>=0 || q.indexOf('\\')>=0))return Optional.empty();
        return select(sourceId,query(type,q,sort,filters,page),page);
    }
    @Override public Optional<Selection> suggest(String sourceId,String prefix) {
        if(prefix==null || prefix.isEmpty() || !prefix.equals(ProductNamePrefix.normalize(prefix)))return Optional.empty();
        var page=PageCriteria.of(1,ProductNamePrefix.LIMIT,ProductNamePrefix.LIMIT);
        var query=Map.of("from",0,"size",ProductNamePrefix.LIMIT,"_source",false,"track_total_hits",true,
                "query",Map.of("bool",Map.of("filter",List.of(Map.of("term",Map.of("deleted",false)),
                    Map.of("prefix",Map.of("name.raw",Map.of("value",prefix,"case_insensitive",true)))))),
                "sort",List.of(Map.of("name.raw","asc"),Map.of("product_id","asc")));
        return select(sourceId,query,page);
    }
    private Optional<Selection> select(String sourceId,Object query,PageCriteria page) {
        try {
            if(sourceId==null || !UUID.fromString(sourceId).toString().equals(sourceId))return Optional.empty();
            BoundTarget binding = aliasBinding == null ? null : aliasBinding.get();
            if (aliasBinding != null && (binding == null || !sourceId.equals(binding.sourceId())
                    || !physicalIndex(binding.indexName()) || !binding.indexUuid().matches("[A-Za-z0-9_-]{1,128}"))) return Optional.empty();
            long end=System.nanoTime()+deadline.toNanos();
            String physical = binding == null ? index : binding.indexName();
            String generation = generation(physical, sourceId, binding, end);
            var result = selection(exchange("POST", "/" + index + "/_search?allow_partial_search_results=false", query, end), page, physical);
            if (!generation.equals(generation(physical, sourceId, binding, end))) return Optional.empty();
            if (binding != null && !binding.equals(aliasBinding.get())) return Optional.empty();
            return Optional.of(result);
        } catch(InterruptedException interrupted){Thread.currentThread().interrupt();return Optional.empty();}
        catch(java.io.IOException|ExecutionException|TimeoutException|IllegalArgumentException invalid){return Optional.empty();}
    }
    private String generation(String physical, String sourceId, BoundTarget binding, long end)
            throws java.io.IOException, InterruptedException, ExecutionException, TimeoutException {
        if (binding != null) {
            var alias = exchange("GET", "/_alias/" + READ_ALIAS, null, end);
            var physicalAliases = alias.path(physical);
            var aliases = physicalAliases.path("aliases");
            var entry = aliases.path(READ_ALIAS);
            var filter = entry.path("filter");
            var term = filter.path("term");
            require(alias.isObject() && alias.size() == 1 && physicalAliases.isObject() && physicalAliases.size() == 1
                && aliases.isObject() && aliases.size() == 1 && entry.isObject() && entry.size() == 2
                && entry.path("is_write_index").isBoolean() && !entry.path("is_write_index").booleanValue()
                && filter.isObject() && filter.size() == 1 && term.isObject() && term.size() == 1
                && term.path("deleted").isBoolean() && !term.path("deleted").booleanValue());
        }
        var body = exchange("GET", "/" + physical + "?filter_path=*.settings.index.uuid,*.mappings._meta", null, end);
        String uuid = metadata(body, physical, sourceId);
        if (binding != null) require(binding.indexUuid().equals(uuid));
        return uuid;
    }
    private String metadata(JsonNode body,String physical,String sourceId){
        require(body.isObject() && body.size()==1 && body.has(physical));
        var meta=body.path(physical).path("mappings").path("_meta");
        require(meta.isObject() && text(meta.get("owner"),"pubburi") && text(meta.get("schema"),"products-v1")
                && text(meta.get("data_kind"),"backend_product_events") && text(meta.get("source_id"),sourceId));
        var uuid=body.path(physical).path("settings").path("index").path("uuid");require(uuid.isTextual() && !uuid.textValue().isBlank() && uuid.textValue().length()<=128);
        return uuid.textValue();
    }
    private Selection selection(JsonNode body,PageCriteria page,String physical){
        require(body.isObject() && body.path("timed_out").isBoolean() && !body.path("timed_out").booleanValue()
                && body.path("_shards").path("failed").isIntegralNumber() && body.path("_shards").path("failed").canConvertToInt() && body.path("_shards").path("failed").intValue()==0
                && body.path("_shards").path("total").isIntegralNumber() && body.path("_shards").path("total").canConvertToInt() && body.path("_shards").path("total").intValue()==1
                && body.path("_shards").path("successful").equals(body.path("_shards").path("total")));
        var hits=body.path("hits");var total=hits.path("total");
        require(text(total.get("relation"),"eq") && total.path("value").isIntegralNumber() && total.path("value").canConvertToInt() && total.path("value").intValue()>=0 && hits.path("hits").isArray());
        int count=total.path("value").intValue();long offset=((long)page.page()-1)*page.size();
        require(hits.path("hits").size()==Math.min(page.size(),Math.max(0L,count-offset)));
        var ids=new ArrayList<Integer>();var unique=new HashSet<Integer>();
        for(var hit:hits.path("hits")){
            var id=hit.path("_id");require(id.isTextual() && id.textValue().matches("[1-9][0-9]{0,9}") && text(hit.get("_index"),physical) && !hit.has("_source"));
            int value=Integer.parseInt(id.textValue());require(value>0 && unique.add(value));ids.add(value);
        }
        return new Selection(List.copyOf(ids),count);
    }
    public Map<String,Object> query(String type,String q,String sort,ProductSearchFilters filters,PageCriteria page){
        var clauses=new ArrayList<Object>();clauses.add(Map.of("term",Map.of("deleted",false)));
        if(type!=null)clauses.add(Map.of("term",Map.of("category",type)));
        var price=new LinkedHashMap<String,Object>();if(filters.minPriceKrw()!=null)price.put("gte",filters.minPriceKrw());if(filters.maxPriceKrw()!=null)price.put("lte",filters.maxPriceKrw());if(!price.isEmpty())clauses.add(Map.of("range",Map.of("price_krw",price)));
        var abv=new LinkedHashMap<String,Object>();if(filters.minAbvPercent()!=null)abv.put("gte",filters.minAbvPercent().floatValue());if(filters.maxAbvPercent()!=null)abv.put("lte",filters.maxAbvPercent().floatValue());if(!abv.isEmpty())clauses.add(Map.of("range",Map.of("abv_percent",abv)));
        tags(clauses,"taste",filters.tasteTags());tags(clauses,"aroma",filters.aromaTags());
        if(q!=null){String pattern="*"+q.replace("*","\\*").replace("?","\\?")+"*";clauses.add(Map.of("bool",Map.of("minimum_should_match",1,"should",List.of(
                Map.of("wildcard",Map.of("name.raw",Map.of("value",pattern,"case_insensitive",true))),
                Map.of("wildcard",Map.of("category",Map.of("value",pattern,"case_insensitive",true)))))));}
        String field="priceAsc".equals(sort)||"priceDesc".equals(sort)?"price_krw":"name".equals(sort)?"name.raw":"order_count";
        String order="priceAsc".equals(sort)||"name".equals(sort)?"asc":"desc";
        return Map.of("from",((long)page.page()-1)*page.size(),"size",page.size(),"_source",false,"track_total_hits",true,
                "query",Map.of("bool",Map.of("filter",clauses)),"sort",List.of(Map.of(field,order),Map.of("product_id","asc")));
    }
    private void tags(List<Object> clauses,String kind,List<String> tags){if(!tags.isEmpty()){clauses.add(Map.of("term",Map.of(kind+"_known",true)));for(String tag:tags)clauses.add(Map.of("term",Map.of(kind+"_tags",tag)));}}
    private JsonNode exchange(String method,String path,Object payload,long end)throws java.io.IOException,InterruptedException,ExecutionException,TimeoutException{
        long remaining=end-System.nanoTime();if(remaining<=0)throw new TimeoutException();
        var request=HttpRequest.newBuilder(endpoint.resolve(path)).timeout(Duration.ofNanos(remaining)).header("Accept","application/json");
        if(payload==null)request.GET();else request.header("Content-Type","application/json").method(method,HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(payload)));
        var active=new AtomicReference<LimitedBody>();var cancelled=new AtomicBoolean();CompletableFuture<HttpResponse<byte[]>> future=null;
        try{
            future=http.sendAsync(request.build(),info->{var body=new LimitedBody();active.set(body);boolean accepted=info.statusCode()==200 && "application/json".equalsIgnoreCase(info.headers().firstValue("Content-Type").orElse("").split(";",2)[0].trim());
                try{accepted &= info.headers().firstValueAsLong("Content-Length").orElse(0)<=MAX_RESPONSE_BYTES;}catch(NumberFormatException bad){accepted=false;}
                if(!accepted || cancelled.get())body.abort();return body;});
            remaining=end-System.nanoTime();if(remaining<=0)throw new TimeoutException();return mapper.readTree(future.get(remaining,TimeUnit.NANOSECONDS).body());
        }finally{cancelled.set(true);if(future!=null && !future.isDone())future.cancel(true);var body=active.get();if(body!=null)body.abort();}
    }
    private static boolean text(JsonNode node,String expected){return node!=null && node.isTextual() && expected.equals(node.textValue());}
    private static boolean physicalIndex(String name) {
        return name != null && !READ_ALIAS.equals(name) && name.matches("pubburi-products-v1(?:-[a-z0-9]{1,32})?");
    }
    private static void require(boolean valid){if(!valid)throw new IllegalArgumentException("Invalid search response");}
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]>{
        final CompletableFuture<byte[]> result=new CompletableFuture<>();final ByteArrayOutputStream bytes=new ByteArrayOutputStream();Flow.Subscription subscription;
        @Override public CompletionStage<byte[]> getBody(){return result;}
        @Override public synchronized void onSubscribe(Flow.Subscription value){subscription=value;if(result.isDone())value.cancel();else value.request(1);}
        @Override public synchronized void onNext(List<ByteBuffer> buffers){if(result.isDone())return;for(var buffer:buffers){if((long)bytes.size()+buffer.remaining()>MAX_RESPONSE_BYTES){abort();return;}byte[] data=new byte[buffer.remaining()];buffer.get(data);bytes.writeBytes(data);}subscription.request(1);}
        @Override public synchronized void onError(Throwable error){result.completeExceptionally(new IllegalStateException("Search unavailable"));}
        @Override public synchronized void onComplete(){result.complete(bytes.toByteArray());}
        synchronized void abort(){result.completeExceptionally(new IllegalStateException("Search unavailable"));if(subscription!=null)subscription.cancel();}
    }
}
