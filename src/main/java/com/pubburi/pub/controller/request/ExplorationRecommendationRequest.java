package com.pubburi.pub.controller.request;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.swagger.v3.oas.annotations.media.Schema;
import java.io.IOException;

@JsonDeserialize(using = ExplorationRecommendationRequest.InputDeserializer.class)
@Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record ExplorationRecommendationRequest(
        @Schema(minimum = "1", maximum = "12", defaultValue = "4", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        int limit) {
    public static class InputDeserializer extends JsonDeserializer<ExplorationRecommendationRequest> {
        @Override
        public ExplorationRecommendationRequest getNullValue(DeserializationContext context) throws JsonMappingException {
            return context.reportInputMismatch(ExplorationRecommendationRequest.class, "Exploration object is required");
        }

        @Override
        public ExplorationRecommendationRequest deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            JsonNode body = parser.getCodec().readTree(parser);
            if (body == null || !body.isObject() || body.size() != (body.has("limit") ? 1 : 0) || parser.nextToken() != null) {
                throw new IllegalArgumentException("Invalid exploration input");
            }
            if (!body.has("limit")) return new ExplorationRecommendationRequest(4);
            var limit = body.get("limit");
            if (!limit.isIntegralNumber() || !limit.canConvertToInt() || limit.intValue() < 1 || limit.intValue() > 12) {
                throw new IllegalArgumentException("Invalid exploration limit");
            }
            return new ExplorationRecommendationRequest(limit.intValue());
        }
    }
}
