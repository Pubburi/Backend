package com.pubburi.pub.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

class SimilarProductContractTests {
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
