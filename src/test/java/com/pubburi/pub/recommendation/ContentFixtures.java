package com.pubburi.pub.recommendation;

import java.time.Instant;
import java.util.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.pubburi.pub.controller.response.UserPreferencesResponse;
import com.pubburi.pub.model.dto.UserPreferences;

/** Authored consumer facts; not a model-generated ranking oracle. */
public final class ContentFixtures {
    public static final ObjectMapper JSON=new ObjectMapper();
    public static final Instant NOW=Instant.parse("2026-11-04T07:30:00Z");
    private ContentFixtures() { }
    public static UserPreferencesResponse preferences(){var p=new UserPreferences();p.setSurveyStatus("COMPLETED");p.setCategories(List.of("맥주"));p.setTasteTags(List.of("a,b"));p.setAromaTags(List.of());return UserPreferencesResponse.from(p);}
    public static ObjectNode bundle()throws Exception{
        var product=JSON.readTree("{\"id\":81,\"name\":\"Authored\",\"type\":\"맥주\",\"price\":1000,\"img\":\"authored.png\",\"abv\":0,\"orderCount\":0}");
        var features=JSON.readTree("{\"productId\":81,\"volumeMl\":750,\"description\":null,\"tasteTags\":[\"a,b\",\"line\\nx\"],\"aromaTags\":[],\"sourceName\":\"Authored\",\"sourceUrl\":null,\"sourceNote\":null}");
        var root=JSON.createObjectNode();root.put("schema_version",1);root.put("algorithm","checked-backend-demo-bundle-v1");root.put("valid_until_utc",NOW.plusSeconds(240).toString());flags(root);
        var catalog=root.putArray("catalog");catalog.addObject().put("product_id",81).put("available",true).put("sku_group","authored-only").put("category","맥주").put("price_krw",1000).put("abv_percent",0).put("volume_ml",750).putNull("taste_tags").putNull("aroma_tags");
        var record=root.putArray("records").addObject();record.put("catalog_record_id","authored:one");record.put("backend_product_id",81);record.put("sku_group","authored-only");record.put("price_kind","demo_snapshot_krw");record.put("eligibility_scope","public_browse_row_exists");
        record.set("product",JSON.createObjectNode().put("message","ok").putNull("error").set("data",product));record.set("features",JSON.createObjectNode().put("message","ok").putNull("error").set("data",features));
        var checked=root.putObject("checked_export");checked.put("algorithm","checked-reviewed-demo-export-v1");checked.put("mode","fresh_demo_snapshot");checked.put("checked_at_utc",NOW.minusSeconds(10).toString());checked.put("original_export_at_utc",NOW.minusSeconds(15).toString());checked.put("snapshot_origin","owned_demo_fixture");checked.put("data_scope","reviewed_mapped_business_demo_only");checked.put("evidence_kind","authored_regression_fixture");flags(checked);
        for(String field:List.of("source_catalog_sha256","business_snapshot_sha256","mapping_sha256"))checked.put(field,"a".repeat(64));
        var hashes=checked.putObject("artifact_sha256");for(String field:List.of("receipt.json","export-report.json","b0-demo-request.json","b1-demo-request.json"))hashes.put(field,"b".repeat(64));return root;
    }
    public static ObjectNode familyBundle()throws Exception{
        var root=bundle();root.withObject("/checked_export").put("data_scope","reviewed_source_family_browse_demo_only").put("snapshot_origin","demo_fixture");
        root.withObject("/records/0").put("eligibility_scope","reviewed_source_family_browse_demo_only");
        root.withObject("/catalog/0").putNull("volume_ml");
        var features=root.withObject("/records/0/features/data");for(String key:List.of("volumeMl","description","tasteTags","aromaTags"))features.putNull(key);
        return root;
    }
    private static void flags(ObjectNode root){root.put("service_catalog_approved",false);root.put("model_loaded",false);root.put("current_business_revalidation_required",true);}
    public static ContentCatalogSource.Catalog catalog()throws Exception{return PinnedContentCatalog.validate(bundle(),"c".repeat(64),NOW);}
    public static ObjectNode twoBundle()throws Exception{var root=bundle();var product=root.withArray("catalog").get(0).deepCopy();((ObjectNode)product).put("product_id",82).put("category","증류주");root.withArray("catalog").add(product);var record=root.withArray("records").get(0).deepCopy();((ObjectNode)record).put("backend_product_id",82).put("catalog_record_id","authored:two");record.withObject("/product/data").put("id",82).put("type","증류주");record.withObject("/features/data").put("productId",82);root.withArray("records").add(record);return root;}
    public static ObjectNode response()throws Exception{return (ObjectNode)JSON.readTree("""
        {"algorithm":"b1-tfidf-minmax-v1","fit_scope":"request_catalog","representation_fitted":true,"model_fingerprint":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","eligible_count":1,"supported_profile_tokens":1,"unknown_profile_tokens":1,"active_numeric_preferences":[],"fallback_reason":null,"items":[{"product_id":81,"score":0.5,"reasons":["available","content_similarity"],"contributions":[{"component":"text","feature":"category:맥주","contribution":0.5}],"explanation":{"version":"r04-facts-v1","basis":"content_similarity","comparison":{"kind":"profile"},"score_evidence":[{"kind":"category_match","label":"주종 일치","actual_value":"맥주","target_value":"맥주","contribution":0.5}],"total_positive_contributions":1,"score_evidence_truncated":false,"filter_evidence":[{"kind":"availability_filter","label":"입력 가용 조건 충족","actual_value":true}],"fallback_reason":null}}]}
        """);}
}
