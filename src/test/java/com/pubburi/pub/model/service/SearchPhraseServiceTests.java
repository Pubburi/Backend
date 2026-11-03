package com.pubburi.pub.model.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.List;
import java.util.Optional;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pubburi.pub.model.dto.UserPreferences;
import com.pubburi.pub.recommendation.SearchPhraseFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class SearchPhraseServiceTests {
    @Test void ownSnapshotIsTheOnlyOutboundDataAndUnavailableNeverGeneratesAWeakerQuery() throws Exception {
        var preferences = mock(UserPreferenceService.class); var stored = SearchPhraseFixtures.preferences();
        when(preferences.getOwned("own", "epoch")).thenReturn(stored);
        var service = new SearchPhraseService(preferences, (snapshot, fields, limit) -> {
            assertThat(fields).containsExactly("tasteTags"); assertThat(limit).isEqualTo(5);
            assertThat(new ObjectMapper().valueToTree(snapshot).toString()).doesNotContain("own", "epoch");
            return Optional.empty();
        }, new ObjectMapper());
        var result = service.suggest("own", "epoch", List.of("tasteTags"), 5);
        assertThat(result.source().name()).isEqualTo("UNAVAILABLE"); assertThat(result.result()).isNull();
        verify(preferences, times(2)).getOwned("own", "epoch");
    }
    @Test void changedPreferencesExpiredEpochAndDbFailureRemainDifferentOutcomes() throws Exception {
        var preferences = mock(UserPreferenceService.class); var initial = SearchPhraseFixtures.preferences();
        var changed = SearchPhraseFixtures.preferences(); changed.setMaxPriceKrw(1);
        var service = new SearchPhraseService(preferences, (snapshot, fields, limit) -> Optional.empty(), new ObjectMapper());
        when(preferences.getOwned("own", "epoch")).thenReturn(initial, changed);
        assertThatThrownBy(() -> service.suggest("own", "epoch", List.of(), 5)).isInstanceOfSatisfying(ResponseStatusException.class, error -> assertThat(error.getStatusCode().value()).isEqualTo(409));
        when(preferences.getOwned("own", "epoch")).thenReturn(initial, (UserPreferences)null);
        assertThatThrownBy(() -> service.suggest("own", "epoch", List.of(), 5)).isInstanceOfSatisfying(ResponseStatusException.class, error -> assertThat(error.getStatusCode().value()).isEqualTo(401));
        when(preferences.getOwned("own", "epoch")).thenThrow(new IllegalStateException("DB failure"));
        assertThatThrownBy(() -> service.suggest("own", "epoch", List.of(), 5)).isInstanceOf(IllegalStateException.class);
    }
    @Test void canonicalProductFilterRejectionRemains400RatherThanUnavailableOrAWeakerQuery() throws Exception {
        var stored=SearchPhraseFixtures.preferences();stored.setTasteTags(List.of("k","K"));
        var response=SearchPhraseFixtures.selectedTaste();var condition=(com.fasterxml.jackson.databind.node.ObjectNode)response.at("/items/0/conditions");
        condition.putArray("tasteTag").add("k").add("K");
        ((com.fasterxml.jackson.databind.node.ObjectNode)response.at("/items/0/query_pairs/4")).put("value","k");
        ((com.fasterxml.jackson.databind.node.ObjectNode)response.at("/items/0/query_pairs/5")).put("value","K");
        ((com.fasterxml.jackson.databind.node.ObjectNode)response.at("/items/0")).put("backend_unicode_revalidation_required",true);
        response.withArray("diagnostics").addObject().put("field","tasteTags").put("code","backend_unicode_revalidation_required");
        var preferences=mock(UserPreferenceService.class);when(preferences.getOwned("own","epoch")).thenReturn(stored);
        var service=new SearchPhraseService(preferences,(snapshot,fields,limit)->Optional.of(response),new ObjectMapper());
        assertThatThrownBy(()->service.suggest("own","epoch",List.of("tasteTags"),5)).isInstanceOfSatisfying(ResponseStatusException.class,error->assertThat(error.getStatusCode().value()).isEqualTo(400));
        ((com.fasterxml.jackson.databind.node.ObjectNode)response.at("/items/0/query_pairs/0")).put("name","url");
        assertThat(service.suggest("own","epoch",List.of("tasteTags"),5).source().name()).isEqualTo("UNAVAILABLE");
    }
}
