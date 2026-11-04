package com.pubburi.pub.recommendation;

import java.math.BigDecimal;
import java.util.List;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pubburi.pub.model.dto.UserPreferences;

public final class SearchPhraseFixtures {
    private SearchPhraseFixtures() { }
    public static UserPreferences preferences() {
        var value = new UserPreferences(); value.setSurveyStatus("COMPLETED");
        value.setCategories(List.of("맥주")); value.setTasteTags(List.of("a,b", "line\nx")); value.setAromaTags(List.of());
        value.setTargetAbvPercent(new BigDecimal("13.70")); value.setMinPriceKrw(0); value.setMaxPriceKrw(20000);
        value.setMinAbvPercent(new BigDecimal("0.00")); value.setMaxAbvPercent(new BigDecimal("20.00")); return value;
    }
    public static ObjectNode selectedTaste() throws Exception {
        return (ObjectNode)new ObjectMapper().readTree("""
            {"algorithm":"r06-explicit-search-phrases-v1","items":[{
              "template_id":"selected_preferences","label":"선택한 취향 조건으로 검색",
              "conditions":{"type":null,"minPriceKrw":0,"maxPriceKrw":20000,"minAbvPercent":"0.00","maxAbvPercent":"20.00","tasteTag":["a,b","line\\nx"],"aromaTag":[]},
              "query_pairs":[{"name":"minPriceKrw","value":"0"},{"name":"maxPriceKrw","value":"20000"},{"name":"minAbvPercent","value":"0.00"},{"name":"maxAbvPercent","value":"20.00"},{"name":"tasteTag","value":"a,b"},{"name":"tasteTag","value":"line\\nx"}],
              "backend_unicode_revalidation_required":false}],"candidate_count":1,"omitted_count":0,"product_count":null,
              "diagnostics":[{"field":"categories","code":"soft_not_selected"},{"field":"aromaTags","code":"soft_not_selected"},{"field":"targetAbvPercent","code":"soft_not_selected"}],"fallback_reason":null}
            """);
    }
    public static ObjectNode allProducts() throws Exception {
        return (ObjectNode)new ObjectMapper().readTree("""
            {"algorithm":"r06-explicit-search-phrases-v1","items":[{"template_id":"all_products","label":"전체 상품 보기","conditions":{"type":null,"minPriceKrw":null,"maxPriceKrw":null,"minAbvPercent":null,"maxAbvPercent":null,"tasteTag":[],"aromaTag":[]},"query_pairs":[],"backend_unicode_revalidation_required":false}],"candidate_count":1,"omitted_count":0,"product_count":null,"diagnostics":[],"fallback_reason":"no_explicit_search_conditions"}
            """);
    }
}
