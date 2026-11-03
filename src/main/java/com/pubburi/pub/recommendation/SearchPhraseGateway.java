package com.pubburi.pub.recommendation;

import java.util.List;
import java.util.Optional;
import com.fasterxml.jackson.databind.JsonNode;
import com.pubburi.pub.controller.response.UserPreferencesResponse;

@FunctionalInterface
public interface SearchPhraseGateway {
    Optional<JsonNode> suggest(UserPreferencesResponse preferences, List<String> searchFields, int limit);
}
