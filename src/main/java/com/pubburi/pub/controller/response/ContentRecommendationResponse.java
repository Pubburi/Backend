package com.pubburi.pub.controller.response;

import java.util.List;
import java.math.BigDecimal;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(requiredProperties={"source","catalogScope","result","products"})
public record ContentRecommendationResponse(Source source, CatalogScope catalogScope,
        @Schema(types={"object","null"}) Result result,List<ProductResponse> products) {
    public enum Source { PROVIDER, UNAVAILABLE }
    public enum CatalogScope { REVIEWED_DEMO }
    public static ContentRecommendationResponse unavailable(){return new ContentRecommendationResponse(Source.UNAVAILABLE,CatalogScope.REVIEWED_DEMO,null,List.of());}
    @Schema(name="ContentRecommendationResult",requiredProperties={"algorithm","fit_scope","representation_fitted","model_fingerprint","items","eligible_count","supported_profile_tokens","unknown_profile_tokens","active_numeric_preferences","fallback_reason"})
    public record Result(String algorithm,@JsonProperty("fit_scope") String fitScope,
            @JsonProperty("representation_fitted") boolean representationFitted,
            @JsonProperty("model_fingerprint") @Schema(types={"string","null"}) String modelFingerprint,
            List<Item> items,@JsonProperty("eligible_count") int eligibleCount,
            @JsonProperty("supported_profile_tokens") int supportedProfileTokens,
            @JsonProperty("unknown_profile_tokens") int unknownProfileTokens,
            @JsonProperty("active_numeric_preferences") List<String> activeNumericPreferences,
            @JsonProperty("fallback_reason") @Schema(types={"string","null"}) String fallbackReason) { }
    @Schema(name="ContentRecommendationItem")
    public record Item(@JsonProperty("product_id") int productId,BigDecimal score,List<String> reasons,
            List<Contribution> contributions,
            @Schema(description="Validated r04-facts-v1 profile evidence; exact original typed facts, never HTML") JsonNode explanation) { }
    @Schema(name="ContentRecommendationContribution")
    public record Contribution(String component,String feature,BigDecimal contribution) { }
}
