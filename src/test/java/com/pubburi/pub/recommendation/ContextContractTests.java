package com.pubburi.pub.recommendation;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import java.util.function.Consumer;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pubburi.pub.controller.response.UserPreferencesResponse.PreferenceHardResponse;
import org.junit.jupiter.api.Test;

class ContextContractTests {
    final ObjectMapper json=new ObjectMapper();
    JsonNode sample()throws Exception{try(var input=getClass().getResourceAsStream("/recommendation/context-contract.json")){return json.readTree(input);}}
    final PreferenceHardResponse hard=new PreferenceHardResponse(1000,2000,null,null);
    @Test void actualAuthoredProviderWireKeepsAllReferenceGroupsHardBoundsAndWinningFacts()throws Exception{
        // Captured from the current provider with authored products; not recommendation quality evidence.
        var sample=sample();var result=new ContextContract(json).validate(sample.get("response"),sample.get("catalog"),List.of(10,20),hard,List.of(50),4);
        assertThat(result.ids()).containsExactly(30,40);assertThat(result.result()).isEqualTo(sample.get("response"));
    }
    @Test void forgedReferencesMetadataGroupsHardBoundariesScoresAndFactsAreRejected()throws Exception{
        var sample=sample();var catalog=sample.get("catalog");var response=(ObjectNode)sample.get("response");var contract=new ContextContract(json);
        for(Consumer<ObjectNode> mutation:List.<Consumer<ObjectNode>>of(
            value->value.put("extra",true),value->value.put("eligible_count",3),value->value.put("evaluated_reference_count",1),
            value->value.put("excluded_reference_group_count",1),value->value.put("min_relevance_score",.5),
            value->value.withObject("/references/0").put("state","missing"),
            value->value.withObject("/references/1").put("supported_profile_tokens",2),
            value->value.withObject("/references/0").put("fallback_reason","no_matching_features"),
            value->value.withObject("/matches/0").put("reference_product_id",20),
            value->value.withObject("/matches/0").put("product_id",40),
            value->value.withObject("/items/0").put("product_id",50),
            value->value.withObject("/items/0").put("score",0),
            value->value.withObject("/items/0/explanation/comparison").put("product_id",20),
            value->value.withObject("/items/0/explanation/score_evidence/0").put("target_value",12),
            value->value.withObject("/items/0/explanation").put("score_evidence_truncated",true),
            value->{value.putArray("items");value.putArray("matches");value.put("fallback_reason","no_matching_features");}
        )){var invalid=response.deepCopy();mutation.accept(invalid);assertThatThrownBy(()->contract.validate(invalid,catalog,List.of(10,20),hard,List.of(50),4)).isInstanceOf(IllegalArgumentException.class);}
    }
    @Test void negativeKnownGroupsIncludeUnavailableRowsWithoutTruncatingAndNormalEmptyIsPreserved()throws Exception{
        var sample=sample();var catalog=sample.get("catalog").deepCopy();((ObjectNode)catalog.get(4)).put("sku_group","candidate-a").put("available",false);
        assertThat(ContextContract.excludedByFeedback(catalog,new FeedbackSignals(List.of(),List.of(50)))).containsExactly(30,50);
        var response=(ObjectNode)sample.get("response").deepCopy();response.putArray("items");response.putArray("matches");response.put("eligible_count",0).put("fallback_reason","no_eligible_products");
        response.withObject("/references/0").put("fallback_reason","no_eligible_products");response.withObject("/references/1").put("fallback_reason","no_eligible_products");
        var result=new ContextContract(json).validate(response,sample.get("catalog"),List.of(10,20),new PreferenceHardResponse(null,0,null,null),List.of(50),4);
        assertThat(result.ids()).isEmpty();assertThat(result.result().path("fallback_reason").textValue()).isEqualTo("no_eligible_products");
    }
}
