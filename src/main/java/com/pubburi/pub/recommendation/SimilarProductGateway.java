package com.pubburi.pub.recommendation;

import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;

public interface SimilarProductGateway {
    Optional<JsonNode> similar(JsonNode catalog, int referenceId, int limit);
}
