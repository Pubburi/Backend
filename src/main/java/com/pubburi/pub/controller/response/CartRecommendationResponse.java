package com.pubburi.pub.controller.response;

import java.util.List;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import com.pubburi.pub.controller.response.ContentRecommendationResponse.CatalogScope;
import com.pubburi.pub.controller.response.ContentRecommendationResponse.Source;

@JsonInclude(JsonInclude.Include.ALWAYS)
@Schema(requiredProperties={"source","catalogScope","cartVersion","references","unavailableReason","result","products"})
public record CartRecommendationResponse(Source source,CatalogScope catalogScope,long cartVersion,
        List<Reference> references,@Schema(types={"string","null"}) UnavailableReason unavailableReason,
        @Schema(types={"object","null"},description="Strictly validated original R07 context response; winning-reference facts, never whole-cart compatibility") JsonNode result,
        List<ProductResponse> products) {
    public enum ReferenceState { READY,DELETED,UNREVIEWED,UNAVAILABLE,CATALOG_UNAVAILABLE }
    public enum UnavailableReason { EMPTY_CART,REFERENCE_LIMIT,DELETED_ITEMS,CATALOG_UNAVAILABLE,
        UNREVIEWED_ITEMS,UNAVAILABLE_REFERENCES,PROVIDER_UNAVAILABLE }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    @Schema(name="CartRecommendationReference",requiredProperties={"productId","name","quantity","state"})
    public record Reference(int productId,@Schema(types={"string","null"}) String name,
            @Schema(minimum="1",maximum="99") int quantity,ReferenceState state) { }
}
