package com.pubburi.pub.recommendation;

import static org.assertj.core.api.Assertions.*;
import java.util.List;
import java.util.function.Consumer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pubburi.pub.controller.response.UserPreferencesResponse;
import com.pubburi.pub.model.dto.UserPreferences;
import org.junit.jupiter.api.Test;

class SearchPhraseContractTests {
    final SearchPhraseContract contract = new SearchPhraseContract(new ObjectMapper());
    @Test void exactHardBoundsAndLiteralAtomsRemainSeparateFromUnselectedSoftTargets() throws Exception {
        var result = contract.validate(SearchPhraseFixtures.selectedTaste(), UserPreferencesResponse.from(SearchPhraseFixtures.preferences()), List.of("tasteTags"), 5);
        var condition = result.items().get(0).conditions();
        assertThat(condition.type()).isNull(); assertThat(condition.minAbvPercent()).isEqualTo("0.00");
        assertThat(condition.maxAbvPercent()).isEqualTo("20.00"); assertThat(condition.minPriceKrw()).isZero();
        assertThat(condition.tasteTag()).containsExactly("a,b", "line\nx"); assertThat(condition.aromaTag()).isEmpty();
        assertThat(result.productCount()).isNull();
    }
    @Test void malformedOrWeakenedProviderResultsAreRejectedWithoutRepair() throws Exception {
        List<Consumer<ObjectNode>> corruptions = List.of(
            root -> root.put("algorithm", "untrusted"), root -> root.put("candidate_count", 0), root -> root.put("product_count", 0),
            root -> root.put("candidate_count", 1.0), root -> root.put("fallback_reason", "no_search_opt_in"),
            root -> root.put("accountId", "private"), root -> root.withArray("items").removeAll(),
            root -> ((ObjectNode)root.at("/items/0/conditions")).putNull("minPriceKrw"),
            root -> ((ObjectNode)root.at("/items/0/conditions")).put("maxAbvPercent", "100.00"),
            root -> ((ObjectNode)root.at("/items/0/conditions")).put("type", "맥주"),
            root -> ((ObjectNode)root.at("/items/0/conditions")).withArray("tasteTag").removeAll(),
            root -> ((ObjectNode)root.at("/items/0/query_pairs/4")).put("value", "a"),
            root -> ((ObjectNode)root.at("/items/0/query_pairs/0")).put("name", "url"),
            root -> ((ObjectNode)root.at("/items/0")).put("backend_unicode_revalidation_required", "false"),
            root -> ((ObjectNode)root.at("/items/0")).put("label", "unverified preference"),
            root -> root.withArray("diagnostics").removeAll());
        for (var corrupt : corruptions) {
            var response = SearchPhraseFixtures.selectedTaste(); corrupt.accept(response);
            assertThatThrownBy(() -> contract.validate(response, UserPreferencesResponse.from(SearchPhraseFixtures.preferences()), List.of("tasteTags"), 5))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
    @Test void unknownAndSkippedAreDifferentFromSelectedEmptyAndNeverBecomeAllProducts() throws Exception {
        for (String status : List.of("NOT_STARTED", "SKIPPED")) {
            var preferences = new UserPreferences(); preferences.setSurveyStatus(status);
            assertThat(contract.validate(SearchPhraseFixtures.allProducts(), UserPreferencesResponse.from(preferences), List.of(), 5).items()).hasSize(1);
            assertThatThrownBy(() -> contract.validate(SearchPhraseFixtures.allProducts(), UserPreferencesResponse.from(preferences), List.of("tasteTags"), 5)).isInstanceOf(IllegalArgumentException.class);
        }
        var response = SearchPhraseFixtures.selectedTaste(); response.withArray("items").removeAll(); response.put("candidate_count", 0);
        response.put("fallback_reason", "selected_preferences_empty"); response.withArray("diagnostics").removeAll().addObject().put("field", "categories").put("code", "soft_not_selected");
        response.withArray("diagnostics").addObject().put("field", "tasteTags").put("code", "soft_not_selected");
        response.withArray("diagnostics").addObject().put("field", "aromaTags").put("code", "selected_empty");
        response.withArray("diagnostics").addObject().put("field", "targetAbvPercent").put("code", "soft_not_selected");
        assertThat(contract.validate(response, UserPreferencesResponse.from(SearchPhraseFixtures.preferences()), List.of("aromaTags"), 5).items()).isEmpty();
    }
    @Test void fiveTemplatesWithAllFortyMaxLengthEscapedAtomsFitTheTransportContract() throws Exception {
        var mapper = new ObjectMapper(); var preferences = new UserPreferences(); preferences.setSurveyStatus("COMPLETED");
        var categories=List.of("과실주","맥주","사케","양주","위스키","증류주","탁주","약 · 청주").stream().sorted().toList();
        var tags=java.util.stream.IntStream.range(0,20).mapToObj(index -> "\u0001".repeat(39)+(char)('a'+index)).toList();
        preferences.setCategories(categories);preferences.setTasteTags(tags);preferences.setAromaTags(tags);
        var root=mapper.createObjectNode();root.put("algorithm","r06-explicit-search-phrases-v1");root.put("candidate_count",8);root.put("omitted_count",3);root.putNull("product_count");root.putNull("fallback_reason");root.putArray("diagnostics");
        var items=root.putArray("items");for(String category:categories.subList(0,5)) {
            var item=items.addObject();item.put("template_id","selected_preferences");item.put("label",category+" 조건으로 검색");item.put("backend_unicode_revalidation_required",false);
            var conditions=item.putObject("conditions");conditions.put("type",category);for(String key:List.of("minPriceKrw","maxPriceKrw","minAbvPercent","maxAbvPercent"))conditions.putNull(key);
            conditions.set("tasteTag",mapper.valueToTree(tags));conditions.set("aromaTag",mapper.valueToTree(tags));
            var pairs=item.putArray("query_pairs");pairs.addObject().put("name","type").put("value",category);
            for(String name:List.of("tasteTag","aromaTag"))for(String tag:tags)pairs.addObject().put("name",name).put("value",tag);
        }
        int bytes=mapper.writeValueAsBytes(root).length;assertThat(bytes).isGreaterThan(65536).isLessThan(SearchPhraseHttpClient.MAX_RESPONSE_BYTES);
        assertThat(contract.validate(root,UserPreferencesResponse.from(preferences),List.of("categories","tasteTags","aromaTags"),5).items()).hasSize(5);
    }
}
