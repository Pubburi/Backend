package com.pubburi.pub.recommendation;

import static com.pubburi.pub.recommendation.ContentWire.*;
import com.pubburi.pub.controller.response.ContentRecommendationResponse.CatalogScope;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;

/** Operator-pinned output of Recommendation's authority loader. Never infers permission. */
public final class PinnedContentCatalog implements ContentCatalogSource {
    public static final int MAX_BUNDLE_BYTES=10485760;
    public static final int MAX_BUNDLES=16;
    public static final String SET_ALGORITHM="checked-backend-demo-bundle-set-v1";
    private final String path,sha256;
    private final Clock clock;
    private final boolean familyBrowseDemoEnabled;
    private final ObjectMapper mapper;
    public PinnedContentCatalog(String path,String sha256,ObjectMapper mapper,Clock clock){
        this(path,sha256,mapper,clock,false);
    }
    public PinnedContentCatalog(String path,String sha256,ObjectMapper mapper,Clock clock,boolean familyBrowseDemoEnabled){
        this.path=path;this.sha256=sha256;this.clock=clock;this.familyBrowseDemoEnabled=familyBrowseDemoEnabled;
        this.mapper=mapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.mapper.getFactory().setStreamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(20).maxStringLength(5000).maxNameLength(128).maxDocumentLength(MAX_BUNDLE_BYTES).build());
    }
    @Override public Optional<Catalog> current(){
        if(path==null || path.isBlank() || sha256==null || !sha256.matches("[a-f0-9]{64}"))return Optional.empty();
        try{
            Path file=Path.of(path);byte[] bytes=read(file,sha256,MAX_BUNDLE_BYTES);
            JsonNode root=mapper.readTree(bytes);require(root!=null && root.isObject());Instant now=clock.instant();
            return Optional.of(text(root.get("algorithm"),SET_ALGORITHM)?compose(root,file,bytes.length,now)
                :validate(root,sha256,now,familyBrowseDemoEnabled));
        }catch(java.io.IOException|java.security.NoSuchAlgorithmException|IllegalArgumentException|DateTimeException invalid){return Optional.empty();}
    }
    static byte[] read(Path file,String expected,int budget)throws java.io.IOException,java.security.NoSuchAlgorithmException{
        require(budget>0 && expected!=null && expected.matches("[a-f0-9]{64}") && Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS));
        byte[] bytes;try(var stream=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)){bytes=stream.readNBytes(budget+1);}
        require(bytes.length<=budget && HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).equals(expected));
        return bytes;
    }
    private Catalog compose(JsonNode root,Path manifest,int consumed,Instant now)throws java.io.IOException,java.security.NoSuchAlgorithmException{
        require(fields(root,"schema_version","algorithm","bundles"));integer(root.get("schema_version"),1,1);
        JsonNode members=root.get("bundles");require(members.isArray() && members.size()>=2 && members.size()<=MAX_BUNDLES);
        Path parent=manifest.toAbsolutePath().getParent().toRealPath();
        var paths=new HashSet<Path>();var keys=new HashSet<String>();var bindings=new LinkedHashMap<Integer,Binding>();
        var catalog=mapper.createArrayNode();JsonNode authority=null;CatalogScope scope=null;Instant until=null;
        for(var member:members){
            require(fields(member,"path","sha256"));string(member.get("path"),1024,false);
            require(member.path("sha256").isTextual());String hash=member.path("sha256").textValue();
            Path relative=Path.of(member.path("path").textValue());require(!relative.isAbsolute());
            Path file=parent.resolve(relative).normalize();require(file.startsWith(parent) && paths.add(file));
            // A local operator descriptor cannot traverse outside its own tree or through links.
            Path current=parent;for(Path part:parent.relativize(file)){current=current.resolve(part);require(!Files.isSymbolicLink(current));}
            byte[] bytes=read(file,hash,MAX_BUNDLE_BYTES-consumed);consumed+=bytes.length;
            JsonNode bundle=mapper.readTree(bytes);Catalog checked=validate(bundle,hash,now,familyBrowseDemoEnabled);
            JsonNode proof=bundle.path("checked_export");
            if(authority==null){authority=proof;scope=checked.scope();}
            else{
                for(String field:List.of("business_snapshot_sha256","snapshot_origin","evidence_kind","data_scope"))require(equal(authority.get(field),proof.get(field)));
                require(scope==checked.scope());
            }
            require(catalog.size()+checked.catalog().size()<=1000);
            for(var record:bundle.path("records"))require(keys.add(record.path("catalog_record_id").textValue()));
            for(var binding:checked.bindings().values())require(bindings.putIfAbsent(binding.productId(),binding)==null);
            checked.catalog().forEach(catalog::add);
            if(until==null || checked.validUntil().isBefore(until))until=checked.validUntil();
        }
        return new Catalog(catalog,Collections.unmodifiableMap(bindings),until,sha256,scope);
    }
    public static Catalog validate(JsonNode root,String sha256,Instant now){return validate(root,sha256,now,false);}
    public static Catalog validate(JsonNode root,String sha256,Instant now,boolean familyBrowseDemoEnabled){
        require(fields(root,"schema_version","algorithm","catalog","records","checked_export","service_catalog_approved","model_loaded","current_business_revalidation_required","valid_until_utc"));
        integer(root.get("schema_version"),1,1);require(text(root.get("algorithm"),"checked-backend-demo-bundle-v1"));flags(root);
        var checked=root.get("checked_export");
        require(fields(checked,"algorithm","mode","checked_at_utc","original_export_at_utc","snapshot_origin","data_scope","evidence_kind","source_catalog_sha256","business_snapshot_sha256","mapping_sha256","artifact_sha256","service_catalog_approved","model_loaded","current_business_revalidation_required"));
        require(text(checked.get("algorithm"),"checked-reviewed-demo-export-v1") && text(checked.get("mode"),"fresh_demo_snapshot"));flags(checked);
        Instant issued=time(checked.get("checked_at_utc")),exported=time(checked.get("original_export_at_utc")),until=time(root.get("valid_until_utc"));
        require(!issued.isAfter(now) && !exported.isAfter(issued) && until.isAfter(now) && until.isAfter(issued) && !until.isAfter(issued.plusSeconds(300)));
        for(String key:List.of("source_catalog_sha256","business_snapshot_sha256","mapping_sha256"))require(checked.path(key).isTextual() && checked.path(key).textValue().matches("[a-f0-9]{64}"));
        boolean family=text(checked.get("data_scope"),"reviewed_source_family_browse_demo_only");
        require(family ? familyBrowseDemoEnabled : text(checked.get("data_scope"),"reviewed_mapped_business_demo_only"));
        boolean authored=text(checked.get("evidence_kind"),"authored_regression_fixture");
        require(authored ? (family ? text(checked.get("snapshot_origin"),"demo_fixture")
                : Set.of("demo_fixture","owned_demo_fixture").contains(checked.path("snapshot_origin").asText()))
            : text(checked.get("evidence_kind"),"reviewed_public_source")
                && text(checked.get("snapshot_origin"),family?"owned_family_browse_demo":"existing_local_catalog"));
        var hashes=checked.get("artifact_sha256");require(fields(hashes,"receipt.json","export-report.json","b0-demo-request.json","b1-demo-request.json"));hashes.forEach(value->require(value.isTextual() && value.textValue().matches("[a-f0-9]{64}")));
        var catalog=root.get("catalog");var records=root.get("records");require(catalog.isArray() && records.isArray() && catalog.size()>0 && catalog.size()<=1000 && catalog.size()==records.size());
        var products=new LinkedHashMap<Integer,JsonNode>();
        for(var product:catalog){
            require(fields(product,"product_id","available","category","price_krw","abv_percent","sku_group","taste_tags","aroma_tags","volume_ml"));
            int id=integer(product.get("product_id"),1,Integer.MAX_VALUE);require(product.path("available").isBoolean() && products.put(id,product)==null);
            string(product.get("sku_group"),128,false);string(product.get("category"),64,true);
            number(product.get("price_krw"),Integer.MAX_VALUE,true);number(product.get("abv_percent"),100,false);
            if(family)require(product.path("volume_ml").isNull() && !product.path("abv_percent").isNull());
            else integer(product.get("volume_ml"),1,1000000);
            // The official export leaves these source facts unknown; DB feature tags are not authority.
            require(product.path("taste_tags").isNull() && product.path("aroma_tags").isNull());
        }
        var bindings=new LinkedHashMap<Integer,Binding>();var keys=new HashSet<String>();
        for(var record:records){
            require(fields(record,"catalog_record_id","backend_product_id","sku_group","price_kind","eligibility_scope","product","features"));
            int id=integer(record.get("backend_product_id"),1,Integer.MAX_VALUE);JsonNode candidate=products.get(id);require(candidate!=null);
            string(record.get("catalog_record_id"),256,false);require(keys.add(record.path("catalog_record_id").textValue()));
            require(equal(record.get("sku_group"),candidate.get("sku_group")) && text(record.get("price_kind"),"demo_snapshot_krw") && text(record.get("eligibility_scope"),family?"reviewed_source_family_browse_demo_only":"public_browse_row_exists"));
            var product=envelope(record.get("product"));var features=envelope(record.get("features"));
            require(fields(product,"id","name","type","price","img","abv","orderCount") && fields(features,"productId","volumeMl","description","tasteTags","aromaTags","sourceName","sourceUrl","sourceNote"));
            integer(product.get("id"),id,id);integer(features.get("productId"),id,id);
            if(family)for(String key:List.of("volumeMl","description","tasteTags","aromaTags"))require(features.path(key).isNull());
            require(equal(candidate.get("category"),product.get("type")) && equal(candidate.get("price_krw"),product.get("price")) && equal(candidate.get("abv_percent"),product.get("abv")) && equal(candidate.get("volume_ml"),features.get("volumeMl")));
            require(bindings.put(id,new Binding(id,product.deepCopy(),features.deepCopy()))==null);
        }
        require(new ArrayList<>(bindings.keySet()).equals(new ArrayList<>(products.keySet())));return new Catalog(catalog.deepCopy(),Collections.unmodifiableMap(bindings),until,sha256,family?CatalogScope.REVIEWED_FAMILY_BROWSE_DEMO:CatalogScope.REVIEWED_DEMO);
    }
    private static JsonNode envelope(JsonNode node){require(fields(node,"data","message","error") && node.path("error").isNull() && node.path("message").isTextual() && node.path("message").textValue().length()<=1000);return node.get("data");}
    private static void flags(JsonNode node){require(node.path("service_catalog_approved").isBoolean() && !node.path("service_catalog_approved").booleanValue() && node.path("model_loaded").isBoolean() && !node.path("model_loaded").booleanValue() && node.path("current_business_revalidation_required").isBoolean() && node.path("current_business_revalidation_required").booleanValue());}
    private static Instant time(JsonNode node){require(node!=null && node.isTextual());try{var time=OffsetDateTime.parse(node.textValue());require(time.getOffset().equals(ZoneOffset.UTC));return time.toInstant();}catch(DateTimeException invalid){throw new IllegalArgumentException("Invalid bundle time");}}
    private static void string(JsonNode value,int max,boolean nullable){require(value!=null && (nullable && value.isNull() || value.isTextual() && !value.textValue().isBlank() && value.textValue().codePointCount(0,value.textValue().length())<=max));}
    private static void number(JsonNode value,int max,boolean integer){require(value!=null && (value.isNull() || value.isNumber() && (!integer || value.isIntegralNumber()) && value.decimalValue().signum()>=0 && value.decimalValue().compareTo(java.math.BigDecimal.valueOf(max))<=0));}
}
