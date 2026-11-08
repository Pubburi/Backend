package com.pubburi.pub.controller.response;

import java.util.List;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.pubburi.pub.controller.response.ContentRecommendationResponse.CatalogScope;
import com.pubburi.pub.controller.response.ContentRecommendationResponse.Source;
import com.pubburi.pub.controller.response.CartRecommendationResponse.ReferenceState;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.ArraySchema;

@JsonInclude(JsonInclude.Include.ALWAYS)
@Schema(requiredProperties={"source","catalogScope","orderId","referenceCount","references","unavailableReason","result","products"})
public record OrderRecommendationResponse(Source source,@Schema(implementation=String.class,allowableValues={"REVIEWED_DEMO","REVIEWED_LOCAL_CATALOG"}) CatalogScope catalogScope,int orderId,@Schema(minimum="0",maximum="2147483647") int referenceCount,
        @ArraySchema(maxItems=20,schema=@Schema(implementation=Reference.class)) List<Reference> references,@Schema(types={"string","null"}) UnavailableReason unavailableReason,
        @Schema(types={"object","null"},description="Validated R07 facts comparing alternatives with one current reference, not the whole historical order") JsonNode result,
        List<ProductResponse> products){
    public OrderRecommendationResponse{references=List.copyOf(references);products=List.copyOf(products);}
    public enum UnavailableReason { EMPTY_ORDER,REFERENCE_LIMIT,DELETED_ITEMS,CATALOG_UNAVAILABLE,UNREVIEWED_ITEMS,UNAVAILABLE_REFERENCES,PROVIDER_UNAVAILABLE }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    @Schema(name="OrderRecommendationReference",requiredProperties={"detailId","productId","name","quantity","state"})
    public record Reference(int detailId,@Schema(types={"integer","null"}) Integer productId,
        @Schema(types={"string","null"}) String name,@Schema(minimum="1",maximum="2147483647") int quantity,ReferenceState state){ }
}
