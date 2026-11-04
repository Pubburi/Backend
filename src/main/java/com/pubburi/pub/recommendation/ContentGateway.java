package com.pubburi.pub.recommendation;

import java.util.Optional;
import com.fasterxml.jackson.databind.JsonNode;
import com.pubburi.pub.controller.response.UserPreferencesResponse;

public interface ContentGateway {
    Optional<JsonNode> recommend(JsonNode catalog,UserPreferencesResponse preferences,int limit);
}
