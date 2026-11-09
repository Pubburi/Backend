package com.pubburi.pub.controller.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.pubburi.pub.controller.response.ContentRecommendationResponse.Item;
import com.pubburi.pub.controller.response.ContentRecommendationResponse.Source;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(requiredProperties = {"source", "catalogScope", "result", "products"})
public record ExplorationRecommendationResponse(Source source, Scope catalogScope,
        @Schema(types = {"object", "null"}) Result result, List<ProductResponse> products) {
    public enum Scope { REVIEWED_DEMO, REVIEWED_LOCAL_CATALOG }

    public static ExplorationRecommendationResponse unavailable(Scope scope) {
        return new ExplorationRecommendationResponse(Source.UNAVAILABLE, scope, null, List.of());
    }

    @Schema(name = "ExplorationRecommendationResult", requiredProperties = {
        "algorithm", "fit_scope", "representation_fitted", "model_fingerprint", "items", "eligible_count",
        "supported_profile_tokens", "unknown_profile_tokens", "active_numeric_preferences", "fallback_reason",
        "exploration_reason", "selections"
    })
    public record Result(String algorithm, @JsonProperty("fit_scope") String fitScope,
            @JsonProperty("representation_fitted") boolean representationFitted,
            @JsonProperty("model_fingerprint") @Schema(types = {"string", "null"}) String modelFingerprint,
            List<Item> items, @JsonProperty("eligible_count") int eligibleCount,
            @JsonProperty("supported_profile_tokens") int supportedProfileTokens,
            @JsonProperty("unknown_profile_tokens") int unknownProfileTokens,
            @JsonProperty("active_numeric_preferences") List<String> activeNumericPreferences,
            @JsonProperty("fallback_reason") @Schema(types = {"string", "null"}) String fallbackReason,
            @JsonProperty("exploration_reason")
            @Schema(allowableValues = {"selected", "baseline_fallback", "below_relevance_floor"}) String explorationReason,
            List<Selection> selections) {
        public static Result from(ContentRecommendationResponse.Result base, String reason, List<Selection> selections) {
            return new Result(base.algorithm(), base.fitScope(), base.representationFitted(), base.modelFingerprint(),
                base.items(), base.eligibleCount(), base.supportedProfileTokens(), base.unknownProfileTokens(),
                base.activeNumericPreferences(), base.fallbackReason(), reason, List.copyOf(selections));
        }
    }

    @Schema(name = "ExplorationRecommendationSelection", requiredProperties = {"product_id", "basis"})
    public record Selection(@JsonProperty("product_id") int productId,
            @Schema(allowableValues = {"highest_relevance_anchor", "observed_text", "unknown_conservative"}) String basis) { }
}
