package com.pubburi.pub.recommendation;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pubburi.pub.controller.response.ExplorationRecommendationResponse.Result;
import com.pubburi.pub.controller.response.ExplorationRecommendationResponse.Selection;
import com.pubburi.pub.controller.response.UserPreferencesResponse;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class ExplorationContractTests {
    private final ObjectMapper mapper = ContentFixtures.JSON;
    private final ExplorationContract contract = new ExplorationContract(mapper, new ContentContract(mapper));

    @Test void actualAuthoredProviderOrderAndProfileFactsSurviveTheConsumerBoundary() throws Exception {
        var fixture = fixture();
        var result = validate(fixture, fixture.get("response"), Set.of(7));
        assertThat(result.explorationReason()).isEqualTo("selected");
        assertThat(result.selections()).containsExactly(new Selection(1, "highest_relevance_anchor"),
            new Selection(4, "observed_text"), new Selection(3, "observed_text"));
        assertThat(result.items().get(1).score()).isLessThan(result.items().get(2).score());
        assertThat(result.items().get(1).explanation().at("/score_evidence/0/actual_value").doubleValue()).isEqualTo(5.25);
        assertThat(result.items().get(1).explanation().at("/comparison/kind").textValue()).isEqualTo("profile");
    }

    @Test void corruptMetadataIneligibleProductsAndUnverifiedFactsRejectTheWholeResponse() throws Exception {
        var fixture = fixture();
        List<Consumer<ObjectNode>> invalid = List.of(
            value -> value.withObject("/policy").put("min_relevance_score", 0.1),
            value -> value.put("selected_group_count", 2),
            value -> value.put("candidate_pool_size", 100),
            value -> value.put("exploration_reason", "below_relevance_floor"),
            value -> value.withObject("/selections/1").put("product_id", 3),
            value -> value.withObject("/selections/0").put("selection_score", 0.4),
            value -> value.withObject("/selections/1").putNull("max_observed_text_similarity"),
            value -> value.withObject("/selections/1").put("basis", "unknown_conservative"),
            value -> value.withObject("/items/1").put("score", 0),
            value -> value.withObject("/items/1/explanation/score_evidence/0").put("actual_value", 7),
            value -> {
                var duplicate = value.withObject("/items/0").deepCopy().put("product_id", 2);
                value.withArray("items").set(2, duplicate);
                value.withObject("/selections/2").put("product_id", 2).put("sku_group", "a");
            });
        for (var mutation : invalid) {
            ObjectNode response = fixture.get("response").deepCopy();
            mutation.accept(response);
            assertThatIllegalArgumentException().isThrownBy(() -> validate(fixture, response, Set.of(7)));
        }
        assertThatIllegalArgumentException().isThrownBy(() -> validate(fixture, fixture.get("response"), Set.of(7, 4)));
    }

    @Test void approvedNegativeGroupsIncludeUnavailableReferencesAndNeverTruncateAtTwenty() {
        var catalog = mapper.createArrayNode();
        catalog.addObject().put("product_id", 1).put("sku_group", "same").put("available", false);
        catalog.addObject().put("product_id", 2).put("sku_group", "same").put("available", true);
        IntStream.rangeClosed(3, 23).forEach(id -> catalog.addObject().put("product_id", id).put("sku_group", "group-" + id));
        var negativeIds = IntStream.rangeClosed(1, 22).filter(id -> id != 2).boxed().toList();
        var signals = new FeedbackSignals(List.of(23), negativeIds);
        assertThat(ContextContract.excludedByFeedback(catalog, signals))
            .containsExactlyElementsOf(IntStream.rangeClosed(1, 22).boxed().toList());
    }

    @Test void numericOnlyCandidateCarriesUnknownTextRatherThanInventedDiversity() throws Exception {
        var fixture = fixture();
        ((ObjectNode) fixture.at("/request/catalog/3")).putNull("category");
        ObjectNode response = fixture.get("response").deepCopy();
        var items = response.withArray("items");
        var numericOnly = (ObjectNode) items.remove(1);
        numericOnly.put("score", 0.475);
        numericOnly.withArray("contributions").remove(1);
        var explanation = numericOnly.withObject("explanation");
        explanation.withArray("score_evidence").remove(1);
        explanation.put("total_positive_contributions", 1);
        items.add(numericOnly);
        var selections = response.withArray("selections");
        var unknown = (ObjectNode) selections.remove(1);
        unknown.put("basis", "unknown_conservative").put("redundancy_penalty_input", 1)
            .putNull("max_observed_text_similarity").put("selection_score", 0.0325);
        selections.add(unknown);

        assertThat(validate(fixture, response, Set.of(7)).selections()).containsExactly(
            new Selection(1, "highest_relevance_anchor"), new Selection(3, "observed_text"), new Selection(4, "unknown_conservative"));
        unknown.put("basis", "observed_text").put("max_observed_text_similarity", 1);
        assertThatIllegalArgumentException().isThrownBy(() -> validate(fixture, response, Set.of(7)));
    }

    private JsonNode fixture() throws Exception {
        // Captured from FastAPI for authored inputs; contains no business or source data.
        try (var input = getClass().getResourceAsStream("/recommendation/exploration-provider.json")) {
            return mapper.readTree(input);
        }
    }

    private Result validate(JsonNode fixture, JsonNode response, Set<Integer> excluded) throws Exception {
        var request = fixture.get("request");
        var preferences = mapper.treeToValue(request.get("preferences"), UserPreferencesResponse.class);
        return contract.validate(response, request.get("catalog"), preferences, request.get("limit").intValue(), excluded);
    }
}
