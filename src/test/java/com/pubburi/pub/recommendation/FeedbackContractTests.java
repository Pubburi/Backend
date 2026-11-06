package com.pubburi.pub.recommendation;

import static org.assertj.core.api.Assertions.*;

import java.util.List;
import java.util.function.Consumer;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

class FeedbackContractTests {
    @Test void onlyActualSavedPositiveEvidenceCanSurviveEveryNegativeGroupAndHardBoundary() throws Exception {
        var json = ContentFixtures.JSON;
        var catalog = json.readTree("""
            [
              {"product_id":81,"sku_group":"candidate","available":true,"category":"맥주","price_krw":1000,"abv_percent":0,"volume_ml":600,"taste_tags":null,"aroma_tags":null},
              {"product_id":82,"sku_group":"matched","available":true,"category":"맥주","price_krw":1000,"abv_percent":0,"volume_ml":500,"taste_tags":null,"aroma_tags":null},
              {"product_id":83,"sku_group":"hidden","available":false,"category":"맥주","price_krw":1000,"abv_percent":0,"volume_ml":1000,"taste_tags":null,"aroma_tags":null},
              {"product_id":84,"sku_group":"hidden","available":true,"category":"맥주","price_krw":1000,"abv_percent":0,"volume_ml":1000,"taste_tags":null,"aroma_tags":null}
            ]
            """);
        var signals = new FeedbackSignals(List.of(82), List.of(83));
        var response = ContentFixtures.response();
        response.put("feedback_algorithm", "r05-max-explicit-feedback-v1").put("ranking_mode", "positive_feedback")
            .put("feedback_group_policy", "exclude_all_known_feedback_groups")
            .put("evaluated_positive_count", 1).put("informative_positive_count", 1)
            .put("excluded_not_interested_count", 2).put("excluded_positive_group_count", 1);
        response.putArray("positive_references").addObject().put("product_id", 82).put("sku_group", "matched")
            .put("state", "available").put("supported_profile_tokens", 1).put("unknown_profile_tokens", 0)
            .putNull("fallback_reason").putArray("active_numeric_preferences").add("volume_ml");
        response.putArray("not_interested_references").addObject().put("product_id", 83).put("sku_group", "hidden")
            .put("state", "unavailable");
        response.putArray("matches").addObject().put("product_id", 81).put("source", "positive_feedback")
            .put("positive_product_id", 82);
        var item = response.withObject("/items/0");
        item.put("score", 0.75);
        item.withArray("contributions").addObject().put("component", "volume_ml").put("feature", "volume_ml").put("contribution", 0.25);
        var explanation = item.withObject("/explanation");
        explanation.withObject("/comparison").put("kind", "reference_product").put("product_id", 82);
        explanation.put("total_positive_contributions", 2);
        explanation.withArray("score_evidence").addObject().put("kind", "volume_comparison").put("label", "용량 비교 근거")
            .put("actual_value", 600).put("target_value", 500).put("unit", "mL").put("contribution", 0.25);

        var contract = new FeedbackContract(new ContentContract(json));
        var preferences = ContentFixtures.preferences();
        var result = contract.validate(response, catalog, preferences, 4, signals);
        assertThat(result.excludedNotInterestedCount()).isEqualTo(2);
        assertThat(result.winningIds()).containsExactly(82);
        assertThat(result.result().items().get(0).explanation().at("/comparison/product_id").intValue()).isEqualTo(82);
        for (Consumer<ObjectNode> mutation : List.<Consumer<ObjectNode>>of(
            value -> value.withObject("/matches/0").put("positive_product_id", 83),
            value -> value.withObject("/positive_references/0").put("state", "blocked"),
            value -> value.withObject("/not_interested_references/0").put("sku_group", "foreign"),
            value -> value.put("excluded_not_interested_count", 1),
            value -> {
                value.withObject("/items/0").put("product_id", 84);
                value.withObject("/matches/0").put("product_id", 84);
            },
            value -> value.withObject("/items/0/explanation/score_evidence/1").put("target_value", 1000),
            value -> {
                value.put("fallback_reason", "no_matching_features");
                value.putArray("items");
                value.putArray("matches");
            }
        )) {
            var invalid = response.deepCopy();
            mutation.accept(invalid);
            assertThatThrownBy(() -> contract.validate(invalid, catalog, preferences, 4, signals))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
