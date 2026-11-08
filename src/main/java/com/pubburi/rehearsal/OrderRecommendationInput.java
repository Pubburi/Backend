package com.pubburi.rehearsal;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.swagger.v3.oas.annotations.media.Schema;
import java.io.IOException;

@JsonDeserialize(using=OrderRecommendationInput.InputDeserializer.class)
@Schema(additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
public record OrderRecommendationInput(@Schema(minimum="1",maximum="12",defaultValue="4",requiredMode=Schema.RequiredMode.NOT_REQUIRED) int limit){
    public static class InputDeserializer extends JsonDeserializer<OrderRecommendationInput>{
        @Override public OrderRecommendationInput getNullValue(DeserializationContext c)throws JsonMappingException{return c.reportInputMismatch(OrderRecommendationInput.class,"Order recommendation object is required");}
        @Override public OrderRecommendationInput deserialize(JsonParser p,DeserializationContext c)throws IOException{
            p.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);JsonNode n=p.getCodec().readTree(p);
            if(n==null || !n.isObject() || n.size()!=(n.has("limit")?1:0) || p.nextToken()!=null)throw new IllegalArgumentException("Invalid order recommendation input");
            if(!n.has("limit"))return new OrderRecommendationInput(4);
            var value=n.get("limit");if(!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue()<1 || value.intValue()>12)throw new IllegalArgumentException("Invalid order recommendation limit");
            return new OrderRecommendationInput(value.intValue());
        }
    }
}
