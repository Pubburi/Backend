package com.pubburi.pub.recommendation;

import java.util.List;
import java.util.Optional;
import com.fasterxml.jackson.databind.JsonNode;
import com.pubburi.pub.controller.response.UserPreferencesResponse.PreferenceHardResponse;

public interface ContextGateway {
    Optional<JsonNode> context(JsonNode catalog,List<Integer> references,PreferenceHardResponse hard,
        List<Integer> excluded,int limit);
}
