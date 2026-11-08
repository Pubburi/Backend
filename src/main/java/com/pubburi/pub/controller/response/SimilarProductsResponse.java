package com.pubburi.pub.controller.response;

import java.util.List;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.pubburi.pub.controller.response.ContentRecommendationResponse.CatalogScope;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(requiredProperties={"items"})
public record SimilarProductsResponse(List<ProductResponse> items,
        @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(implementation=String.class,allowableValues={"REVIEWED_LOCAL_CATALOG"},requiredMode=Schema.RequiredMode.NOT_REQUIRED) CatalogScope catalogScope,
        @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(requiredMode=Schema.RequiredMode.NOT_REQUIRED) List<Explanation> explanations) {
    public SimilarProductsResponse(List<ProductResponse> items){this(items,null,null);}
    public SimilarProductsResponse(List<ProductResponse> items,CatalogScope scope){this(items,scope,null);}
    public SimilarProductsResponse {
        items = List.copyOf(items);
        explanations=explanations==null?null:List.copyOf(explanations);
    }
    @Schema(name="SimilarProductExplanation",requiredProperties={"productId","explanation"})
    public record Explanation(int productId,@Schema(description="Validated r04-facts-v1 facts comparing this candidate with the requested current reference; never HTML") JsonNode explanation){ }
}
