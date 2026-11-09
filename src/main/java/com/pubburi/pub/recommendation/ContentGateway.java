package com.pubburi.pub.recommendation;

import java.util.List;
import java.util.Optional;
import com.fasterxml.jackson.databind.JsonNode;
import com.pubburi.pub.controller.response.UserPreferencesResponse;
import com.pubburi.pub.controller.response.ContentRecommendationResponse.ActivityEvidence;

@FunctionalInterface
public interface ContentGateway {
    Optional<JsonNode> recommend(JsonNode catalog,UserPreferencesResponse preferences,int limit, ActivityEvidence activity);

    default Optional<JsonNode> explore(JsonNode catalog, UserPreferencesResponse preferences, int limit,
            List<Integer> excluded) {
        return Optional.empty();
    }

    default Optional<JsonNode> feedback(JsonNode catalog, UserPreferencesResponse preferences, int limit,
            ActivityEvidence activity, FeedbackSignals signals) {
        return Optional.empty();
    }
}
