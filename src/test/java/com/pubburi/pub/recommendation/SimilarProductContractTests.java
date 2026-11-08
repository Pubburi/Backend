package com.pubburi.pub.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

class SimilarProductContractTests {
    private static com.fasterxml.jackson.databind.JsonNode unicodeProvider()throws Exception{
        // Actual provider output for authored literal facts; not source/model-quality evidence.
        try(var input=SimilarProductContractTests.class.getResourceAsStream("/recommendation/unicode-similar.json")){
            return ContentFixtures.JSON.readTree(input);
        }
    }
    @Test void unicodeProviderContributionOrderIsAcceptedByLegacyCandidates()throws Exception{
        var fixture=unicodeProvider();var root=(ObjectNode)fixture.path("response").deepCopy();root.withObject("/items/0").remove("explanation");
        assertThat(SimilarProductContract.validate(root,fixture.at("/request/catalog"),81,4)).containsExactly(83);
    }
    @Test void unicodeProviderTiedEvidenceUsesCodePointsAndRejectsReorderedParts()throws Exception{
        var fixture=unicodeProvider();var root=(ObjectNode)fixture.path("response").deepCopy();
        assertThat(SimilarProductContract.validateExplained(root,fixture.at("/request/catalog"),81,4,ContentFixtures.JSON)).hasSize(1);
        var wrong=root.deepCopy();var evidence=wrong.withArray("/items/0/explanation/score_evidence");var first=evidence.remove(0);evidence.add(first);
        assertThatThrownBy(()->SimilarProductContract.validateExplained(wrong,fixture.at("/request/catalog"),81,4,ContentFixtures.JSON)).isInstanceOf(IllegalArgumentException.class);
        var parts=root.withArray("/items/0/contributions");var part=parts.remove(0);parts.add(part);
        assertThatThrownBy(()->SimilarProductContract.validateExplained(root,fixture.at("/request/catalog"),81,4,ContentFixtures.JSON)).isInstanceOf(IllegalArgumentException.class);
    }
    private static ArrayNode catalog() throws Exception {
        return (ArrayNode) ContentFixtures.JSON.readTree("""
            [
              {"product_id":81,"sku_group":"anchor","available":true,"category":"맥주","abv_percent":3.2,"volume_ml":500,"taste_tags":null,"aroma_tags":null},
              {"product_id":82,"sku_group":"anchor","available":true,"category":"맥주","abv_percent":3.2,"volume_ml":500,"taste_tags":null,"aroma_tags":null},
              {"product_id":83,"sku_group":"second","available":true,"category":"맥주","abv_percent":5.0,"volume_ml":750,"taste_tags":null,"aroma_tags":null},
              {"product_id":84,"sku_group":"unavailable","available":false,"category":"맥주","abv_percent":4.0,"volume_ml":500,"taste_tags":null,"aroma_tags":null}
            ]
            """);
    }

    static ObjectNode positive() throws Exception {
        return (ObjectNode) ContentFixtures.JSON.readTree("""
            {"algorithm":"b1-tfidf-minmax-v1","fit_scope":"request_catalog","representation_fitted":true,
             "model_fingerprint":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
             "eligible_count":1,"supported_profile_tokens":1,"unknown_profile_tokens":0,
             "active_numeric_preferences":["abv_percent","volume_ml"],"fallback_reason":null,
             "reference_product_id":81,"reference_sku_group":"anchor","reference_state":"available",
             "profile_source":"reference_product_facts","reference_group_policy":"exclude_reference_group",
             "excluded_reference_group_count":2,
             "items":[{"product_id":83,"score":0.3333333333333333,"reasons":["available","content_similarity"],
                       "contributions":[{"component":"text","feature":"category:맥주","contribution":0.3333333333333333}]}]}
            """);
    }
    private static ObjectNode explained()throws Exception{
        var root=positive();var explanation=root.withObject("/items/0").putObject("explanation");
        explanation.put("version","r04-facts-v1").put("basis","content_similarity");explanation.putObject("comparison").put("kind","reference_product").put("product_id",81);
        explanation.putArray("score_evidence").addObject().put("kind","category_match").put("label","주종 일치").put("actual_value","맥주").put("target_value","맥주").put("contribution",1.0/3);
        explanation.put("total_positive_contributions",1).put("score_evidence_truncated",false).putNull("fallback_reason");
        explanation.putArray("filter_evidence").addObject().put("kind","availability_filter").put("label","입력 가용 조건 충족").put("actual_value",true);return root;
    }
    @Test void explainedComparisonKeepsTheExactReferenceAndRejectsFabricatedOrMissingFacts()throws Exception{
        var checked=SimilarProductContract.validateExplained(explained(),catalog(),81,4,ContentFixtures.JSON);
        assertThat(checked).hasSize(1);assertThat(checked.get(0).productId()).isEqualTo(83);
        assertThat(checked.get(0).explanation().at("/comparison/product_id").intValue()).isEqualTo(81);
        for(String mutation:java.util.List.of("reference","kind","actual","label","unit","extra","missing","fallback","truncated")){
            var value=explained();var e=value.withObject("/items/0/explanation");
            switch(mutation){
                case "reference"->e.withObject("/comparison").put("product_id",82);
                case "kind"->e.withObject("/comparison").put("kind","profile");
                case "actual"->e.withObject("/score_evidence/0").put("actual_value","미검수 맛");
                case "label"->e.withObject("/score_evidence/0").put("label","<b>광고</b>");
                case "unit"->e.withObject("/score_evidence/0").put("unit","mL");
                case "extra"->e.put("html","<script>");
                case "missing"->value.withObject("/items/0").remove("explanation");
                case "fallback"->e.put("fallback_reason","no_matching_features");
                case "truncated"->e.put("score_evidence_truncated",true);
            }
            assertThatThrownBy(()->SimilarProductContract.validateExplained(value,catalog(),81,4,ContentFixtures.JSON)).as(mutation).isInstanceOf(IllegalArgumentException.class);
        }
    }
    @Test void emptyExplanationsStillCheckProviderDiagnosticsAndKnownZeroIsNotUnknown()throws Exception{
        var empty=explained();empty.putArray("items");empty.put("fallback_reason","no_matching_features");
        assertThat(SimilarProductContract.validateExplained(empty,catalog(),81,4,ContentFixtures.JSON)).isEmpty();
        empty.put("reference_product_id",999);
        assertThatThrownBy(()->SimilarProductContract.validateExplained(empty,catalog(),81,4,ContentFixtures.JSON)).isInstanceOf(IllegalArgumentException.class);
        var rows=catalog();for(int i=0;i<3;i++)((ObjectNode)rows.get(i)).put("abv_percent",0);((ObjectNode)rows.get(3)).put("abv_percent",10);
        var root=explained();root.withObject("/items/0").put("score",0.5);root.withObject("/items/0/contributions/0").put("component","abv_percent").put("feature","abv_percent").put("contribution",0.5);
        root.withObject("/items/0/explanation").putArray("score_evidence").addObject().put("kind","abv_comparison").put("label","도수 비교 근거").put("actual_value",0).put("target_value",0).put("unit","vol%").put("contribution",0.5);
        assertThat(SimilarProductContract.validateExplained(root,rows,81,4,ContentFixtures.JSON).get(0).explanation().at("/score_evidence/0/target_value").intValue()).isZero();
        root.withObject("/items/0/explanation/score_evidence/0").putNull("target_value");
        assertThatThrownBy(()->SimilarProductContract.validateExplained(root,rows,81,4,ContentFixtures.JSON)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void onlyTheThreeLargestObservedContributionsAreShownAndHiddenEvidenceStillMustBeTrue()throws Exception{
        var rows=catalog();for(int index:java.util.List.of(0,2)){((ObjectNode)rows.get(index)).putArray("taste_tags").add("dry");((ObjectNode)rows.get(index)).putArray("aroma_tags").add("floral");}
        ((ObjectNode)rows.get(2)).put("abv_percent",4);((ObjectNode)rows.get(3)).put("abv_percent",10);
        var root=explained().put("supported_profile_tokens",3);var item=root.withObject("/items/0");item.put("score",0.85);
        var parts=item.putArray("contributions");parts.addObject().put("component","abv_percent").put("feature","abv_percent").put("contribution",0.1);
        for(var part:java.util.List.of(new String[]{"aroma:floral","0.2"},new String[]{"category:맥주","0.3"},new String[]{"taste:dry","0.25"}))
            parts.addObject().put("component","text").put("feature",part[0]).put("contribution",new java.math.BigDecimal(part[1]));
        var e=item.withObject("/explanation").put("total_positive_contributions",4).put("score_evidence_truncated",true);var evidence=e.putArray("score_evidence");
        for(var fact:java.util.List.of(new String[]{"category_match","주종 일치","맥주","0.3"},new String[]{"taste_match","맛 태그 일치","dry","0.25"},new String[]{"aroma_match","향 태그 일치","floral","0.2"}))
            evidence.addObject().put("kind",fact[0]).put("label",fact[1]).put("actual_value",fact[2]).put("target_value",fact[2]).put("contribution",new java.math.BigDecimal(fact[3]));
        assertThat(SimilarProductContract.validateExplained(root,rows,81,4,ContentFixtures.JSON).get(0).explanation().path("score_evidence")).hasSize(3);
        // The fourth numeric part is not displayed, but its endpoint cannot become invented evidence.
        ((ObjectNode)rows.get(2)).put("abv_percent",10);
        assertThatThrownBy(()->SimilarProductContract.validateExplained(root,rows,81,4,ContentFixtures.JSON)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void positiveFactualCandidateKeepsProviderOrderAndNoPositiveItemsStaysEmpty() throws Exception {
        assertThat(SimilarProductContract.validate(positive(), catalog(), 81, 4)).containsExactly(83);
        var response = positive();
        response.putArray("items");
        response.put("fallback_reason", "no_matching_features");
        assertThat(SimilarProductContract.validate(response, catalog(), 81, 4)).isEmpty();
    }

    @Test void equalScoreTiesKeepNumericIdOrder() throws Exception {
        var rows = catalog();
        rows.add(((ObjectNode) rows.get(2)).deepCopy().put("product_id", 85).put("sku_group", "third"));
        var response = positive().put("eligible_count", 2);
        var items = response.withArray("items");
        var second = ((ObjectNode) items.get(0)).deepCopy().put("product_id", 85);
        items.add(second);
        assertThat(SimilarProductContract.validate(response, rows, 81, 4)).containsExactly(83, 85);
        items.remove(1);
        items.insert(0, second);
        assertThatThrownBy(() -> SimilarProductContract.validate(response, rows, 81, 4))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void cannotExposeAnchorVariantUnavailableOrForeignId() throws Exception {
        for (int invalid : new int[]{81, 82, 84, 999}) {
            var response = positive();
            response.withObject("/items/0").put("product_id", invalid);
            assertThatThrownBy(() -> SimilarProductContract.validate(response, catalog(), 81, 4))
                .isInstanceOf(IllegalArgumentException.class);
        }
        var duplicate = positive();
        duplicate.withArray("items").add(duplicate.withArray("items").get(0).deepCopy());
        assertThatThrownBy(() -> SimilarProductContract.validate(duplicate, catalog(), 81, 4))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void zeroFabricatedTextAndEndpointNumericEvidenceAreRejected() throws Exception {
        var zero = positive();
        zero.withObject("/items/0").put("score", 0);
        var falseTag = positive();
        falseTag.withObject("/items/0/contributions/0").put("feature", "taste:unreviewed");
        var numericEndpoint = positive();
        numericEndpoint.withObject("/items/0/contributions/0")
            .put("component", "abv_percent").put("feature", "abv_percent");
        for (var response : new ObjectNode[]{zero, falseTag, numericEndpoint}) {
            assertThatThrownBy(() -> SimilarProductContract.validate(response, catalog(), 81, 4))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
