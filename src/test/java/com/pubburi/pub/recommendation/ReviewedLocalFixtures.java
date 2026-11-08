package com.pubburi.pub.recommendation;

import java.time.Instant;
import java.util.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.pubburi.pub.controller.response.ProductResponse;
import com.pubburi.pub.model.service.OwnedContentSnapshotService.Facts;

/** Authored typed parser/consumer inputs, not an approved aT source or a ranking-quality dataset. */
public final class ReviewedLocalFixtures {
    public static final ObjectMapper JSON=new ObjectMapper();
    public static final Instant NOW=Instant.parse("2026-11-08T12:00:00Z");
    public static final String NAMESPACE="00000000-0000-4000-8000-000000000001";
    private ReviewedLocalFixtures(){ }
    public static ObjectNode authority(){
        var root=JSON.createObjectNode().put("schema_version",1).put("algorithm","at-reviewed-local-catalog-v1")
            .put("scope","REVIEWED_LOCAL_CATALOG").put("catalog_version","authored-local-v1").put("reviewed_at_utc",NOW.minusSeconds(60).toString());
        var source=root.putObject("source").put("source_id","at-traditional-liquor").put("dataset_id","15048755").put("source_version","20260731")
            .put("evidence_kind","operator_public_download_receipt").put("captured_at_utc","2026-10-31T00:00:00Z");
        for(String field:List.of("input_sha256","receipt_sha256","manifest_sha256","review_sha256","selection_sha256"))source.put(field,"a".repeat(64));
        root.putObject("license").put("label","portal-unrestricted-with-additional-notes").put("purpose","noncommercial_local_structured_display_and_fit")
            .put("attribution","한국농수산식품유통공사(aT) 전통주정보").put("source_url","https://www.data.go.kr/data/15048755/fileData.do?recommendDataYn=Y")
            .put("raw_redistribution",false).put("source_media_included",false).put("free_text_included",false);
        var records=root.putArray("records");
        for(int i=1;i<=2;i++){
            var row=records.addObject().put("catalog_record_id","authored-record-"+i).put("source_row_number",i)
                .put("source_record_id","at:15048755:"+"a".repeat(64)+":row:"+i).put("source_variant_group","authored-group-"+i);
            row.putObject("facts").put("label",i==1?"Authored":"Authored two").put("producer_name","Authored producer")
                .put("source_category",i==1?"약주":"과실주").put("category",i==1?"약 · 청주":"과실주")
                .put("abv_percent",i==1?0:10).put("volume_ml",i==1?750:500).putNull("gtin").putNull("taste_tags").putNull("aroma_tags").putNull("image_url").putNull("description");
            row.putObject("evidence").put("url","https://thesool.com/front/publication/M000000066/view.do?bbsId=A000000044&publicationId=C00000000"+i).put("sha256","b".repeat(64));
        }
        return root;
    }
    public static ObjectNode mapping(String authoritySha){
        var root=JSON.createObjectNode().put("schema_version",1).put("algorithm","reviewed-local-catalog-mapping-v1").put("authority_sha256",authoritySha)
            .put("catalog_version","authored-local-v1").put("source_version","20260731").put("business_source_id",NAMESPACE);
        for(int i=1;i<=2;i++)root.withArray("records").addObject().put("catalog_record_id","authored-record-"+i).put("backend_product_id",80+i).put("sku_group","authored-group-"+i);
        return root;
    }
    public static ReviewedLocalCatalog catalog(){
        return new ReviewedLocalCatalog(List.of(new ReviewedLocalCatalog.Product(81,"authored-group-1","Authored","약 · 청주",java.math.BigDecimal.ZERO,750),
            new ReviewedLocalCatalog.Product(82,"authored-group-2","Authored two","과실주",java.math.BigDecimal.TEN,500)),"c".repeat(64),NAMESPACE);
    }
    public static Map<Integer,Facts> current()throws Exception{
        var result=new LinkedHashMap<Integer,Facts>();
        for(var row:catalog().records()){
            var product=new ProductResponse(row.productId(),row.label(),row.category(),row.productId()==81?1000:2000,"authored-placeholder.webp",row.abvPercent().floatValue(),0);
            var features=JSON.createObjectNode().put("productId",row.productId()).put("volumeMl",row.volumeMl()).put("description","Local unreviewed note");
            features.putArray("tasteTags").add("local-only");features.putArray("aromaTags").add("local-only");
            result.put(row.productId(),new Facts(JSON.readTree(JSON.writeValueAsBytes(product)),features,product));
        }
        return result;
    }
    public static com.pubburi.pub.controller.response.UserPreferencesResponse preferences(){
        var p=new com.pubburi.pub.model.dto.UserPreferences();p.setSurveyStatus("COMPLETED");p.setCategories(List.of("약 · 청주"));p.setTasteTags(List.of("a,b"));p.setAromaTags(List.of());
        return com.pubburi.pub.controller.response.UserPreferencesResponse.from(p);
    }
    public static ObjectNode response(int eligible)throws Exception{
        return ((ObjectNode)JSON.readTree(ContentFixtures.response().toString().replace("맥주","약 · 청주"))).put("eligible_count",eligible);
    }
    public static void price(Map<Integer,Facts> facts,int id,int value)throws Exception{
        var old=facts.get(id);var p=old.currentProduct();var updated=new ProductResponse(p.id(),p.name(),p.type(),value,p.img(),p.abv(),p.orderCount());
        facts.put(id,new Facts(JSON.readTree(JSON.writeValueAsBytes(updated)),old.features(),updated));
    }
}
