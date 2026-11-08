package com.pubburi.pub.controller.response;

import java.util.List;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.pubburi.pub.controller.response.ContentRecommendationResponse.CatalogScope;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(requiredProperties={"items"})
public record SimilarProductsResponse(List<ProductResponse> items,
        @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(implementation=String.class,allowableValues={"REVIEWED_LOCAL_CATALOG"},requiredMode=Schema.RequiredMode.NOT_REQUIRED) CatalogScope catalogScope) {
    public SimilarProductsResponse(List<ProductResponse> items){this(items,null);}
    public SimilarProductsResponse {
        items = List.copyOf(items);
    }
}
