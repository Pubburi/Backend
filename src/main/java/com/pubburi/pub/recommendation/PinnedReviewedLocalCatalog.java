package com.pubburi.pub.recommendation;

import static com.pubburi.pub.recommendation.ContentWire.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;

/** Consumes operator-pinned typed output of the aT auditor; does not re-license or infer source facts. */
public final class PinnedReviewedLocalCatalog implements ContentCatalogSource {
    private static final Map<String,String> CATEGORIES=Map.of("약주","약 · 청주","탁주","탁주","과실주","과실주","증류주","증류주");
    private static final String SOURCE_URL="https://www.data.go.kr/data/15048755/fileData.do?recommendDataYn=Y";
    private final String authorityPath,authoritySha,mappingPath,mappingSha;private final ObjectMapper mapper;private final Clock clock;
    public PinnedReviewedLocalCatalog(String authorityPath,String authoritySha,String mappingPath,String mappingSha,ObjectMapper mapper,Clock clock){
        this.authorityPath=authorityPath;this.authoritySha=authoritySha;this.mappingPath=mappingPath;this.mappingSha=mappingSha;this.clock=clock;
        this.mapper=mapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.mapper.getFactory().setStreamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(20).maxStringLength(5000).maxNameLength(128).maxDocumentLength(PinnedContentCatalog.MAX_BUNDLE_BYTES).build());
    }
    @Override public Optional<ReviewedLocalCatalog> current(){
        if(authorityPath==null || authorityPath.isBlank() || mappingPath==null || mappingPath.isBlank())return Optional.empty();
        try{
            byte[] authority=PinnedContentCatalog.read(Path.of(authorityPath),authoritySha,PinnedContentCatalog.MAX_BUNDLE_BYTES);
            byte[] mapping=PinnedContentCatalog.read(Path.of(mappingPath),mappingSha,PinnedContentCatalog.MAX_BUNDLE_BYTES-authority.length);
            return Optional.of(validate(mapper.readTree(authority),mapper.readTree(mapping),authoritySha,mappingSha,clock.instant()));
        }catch(java.io.IOException|java.security.NoSuchAlgorithmException|IllegalArgumentException|DateTimeException invalid){return Optional.empty();}
    }
    static ReviewedLocalCatalog validate(JsonNode root,JsonNode mapping,String authoritySha,String mappingSha,Instant now)throws java.security.NoSuchAlgorithmException{
        require(fields(root,"schema_version","algorithm","scope","catalog_version","reviewed_at_utc","source","license","records"));
        integer(root.get("schema_version"),1,1);require(text(root.get("algorithm"),"at-reviewed-local-catalog-v1") && text(root.get("scope"),"REVIEWED_LOCAL_CATALOG"));
        String version=string(root.get("catalog_version"),128);Instant reviewed=time(root.get("reviewed_at_utc"));require(!reviewed.isAfter(now));
        var source=root.get("source");require(fields(source,"source_id","dataset_id","source_version","evidence_kind","captured_at_utc","input_sha256","receipt_sha256","manifest_sha256","review_sha256","selection_sha256"));
        require(text(source.get("source_id"),"at-traditional-liquor") && text(source.get("dataset_id"),"15048755")
            && text(source.get("evidence_kind"),"operator_public_download_receipt"));
        String sourceVersion=string(source.get("source_version"),8);require(sourceVersion.matches("[0-9]{8}"));
        LocalDate.parse(sourceVersion,java.time.format.DateTimeFormatter.BASIC_ISO_DATE);require(!time(source.get("captured_at_utc")).isAfter(reviewed));
        for(String name:List.of("input_sha256","receipt_sha256","manifest_sha256","review_sha256","selection_sha256"))hash(source.get(name));
        var license=root.get("license");require(fields(license,"label","purpose","attribution","source_url","raw_redistribution","source_media_included","free_text_included")
            && text(license.get("label"),"portal-unrestricted-with-additional-notes")
            && text(license.get("purpose"),"noncommercial_local_structured_display_and_fit")
            && text(license.get("attribution"),"한국농수산식품유통공사(aT) 전통주정보") && text(license.get("source_url"),SOURCE_URL));
        for(String flag:List.of("raw_redistribution","source_media_included","free_text_included"))require(license.path(flag).isBoolean() && !license.path(flag).booleanValue());
        require(fields(mapping,"schema_version","algorithm","authority_sha256","catalog_version","source_version","business_source_id","records"));
        integer(mapping.get("schema_version"),1,1);require(text(mapping.get("algorithm"),"reviewed-local-catalog-mapping-v1")
            && text(mapping.get("authority_sha256"),authoritySha) && text(mapping.get("catalog_version"),version) && text(mapping.get("source_version"),sourceVersion));
        String namespace=string(mapping.get("business_source_id"),36);require(UUID.fromString(namespace).toString().equals(namespace));
        var records=root.get("records");var mapped=mapping.get("records");
        require(records.isArray() && !records.isEmpty() && records.size()<=1000 && mapped.isArray() && mapped.size()==records.size());
        var recordIds=new HashSet<String>();var sourceIds=new HashSet<String>();var rowNumbers=new HashSet<Integer>();var productIds=new HashSet<Integer>();var result=new ArrayList<ReviewedLocalCatalog.Product>();
        for(int i=0;i<records.size();i++){
            var record=records.get(i);var binding=mapped.get(i);
            require(fields(record,"catalog_record_id","source_record_id","source_row_number","source_variant_group","facts","evidence")
                && fields(binding,"catalog_record_id","backend_product_id","sku_group"));
            String id=string(record.get("catalog_record_id"),256),group=string(record.get("source_variant_group"),128);
            int sourceRow=integer(record.get("source_row_number"),1,Integer.MAX_VALUE);String sourceRecord=string(record.get("source_record_id"),512);
            require(sourceRecord.equals("at:15048755:"+source.path("input_sha256").textValue()+":row:"+sourceRow)
                && recordIds.add(id) && sourceIds.add(sourceRecord) && rowNumbers.add(sourceRow)
                && text(binding.get("catalog_record_id"),id) && text(binding.get("sku_group"),group));
            int productId=integer(binding.get("backend_product_id"),1,Integer.MAX_VALUE);require(productIds.add(productId));
            var facts=record.get("facts");require(fields(facts,"label","producer_name","source_category","category","abv_percent","volume_ml","gtin","taste_tags","aroma_tags","image_url","description"));
            String label=string(facts.get("label"),200),category=string(facts.get("category"),64);string(facts.get("producer_name"),200);
            require(category.equals(CATEGORIES.get(string(facts.get("source_category"),64))));
            var abv=facts.get("abv_percent");require(abv!=null && abv.isNumber() && Double.isFinite(abv.doubleValue()) && abv.decimalValue().signum()>=0 && abv.decimalValue().compareTo(java.math.BigDecimal.valueOf(100))<=0);
            int volume=integer(facts.get("volume_ml"),1,1000000);
            for(String field:List.of("gtin","taste_tags","aroma_tags","image_url","description"))require(facts.path(field).isNull());
            var evidence=record.get("evidence");require(fields(evidence,"url","sha256"));hash(evidence.get("sha256"));
            URI url=URI.create(string(evidence.get("url"),2048));require("https".equals(url.getScheme()) && "thesool.com".equals(url.getHost()) && url.getUserInfo()==null && url.getPort()==-1
                && "/front/publication/M000000066/view.do".equals(url.getPath()) && url.getFragment()==null);
            result.add(new ReviewedLocalCatalog.Product(productId,group,label,category,abv.decimalValue(),volume));
        }
        String fingerprint=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((authoritySha+"\0"+mappingSha).getBytes(StandardCharsets.US_ASCII)));
        return new ReviewedLocalCatalog(result,fingerprint,namespace);
    }
    private static String string(JsonNode node,int maximum){
        require(node!=null && node.isTextual() && !node.textValue().isBlank());String value=node.textValue();
        require(value.codePointCount(0,value.length())<=maximum && value.indexOf('\0')<0 && value.codePoints().noneMatch(p->p>=Character.MIN_SURROGATE && p<=Character.MAX_SURROGATE));return value;
    }
    private static void hash(JsonNode node){require(node!=null && node.isTextual() && node.textValue().matches("[a-f0-9]{64}"));}
    private static Instant time(JsonNode node){var value=OffsetDateTime.parse(string(node,64));require(value.getOffset().equals(ZoneOffset.UTC));return value.toInstant();}
}
