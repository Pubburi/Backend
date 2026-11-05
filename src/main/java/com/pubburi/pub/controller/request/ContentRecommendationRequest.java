package com.pubburi.pub.controller.request;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;

@Schema(additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
public final class ContentRecommendationRequest {
    private Integer limit=4;
    private boolean includeActivity;
    @NotNull @Min(1) @Max(12) @Schema(defaultValue="4")
    public Integer getLimit(){return limit;}
    @JsonDeserialize(using=StrictIntegerDeserializer.class)
    public void setLimit(Integer value){limit=value;}
    @Schema(type="boolean", defaultValue="false")
    public boolean getIncludeActivity() { return includeActivity; }
    @JsonSetter(value="includeActivity", nulls=Nulls.FAIL)
    public void setIncludeActivity(JsonNode value) {
        if (!value.isBoolean()) throw new IllegalArgumentException("includeActivity must be a boolean");
        includeActivity = value.booleanValue();
    }
    @JsonAnySetter public void rejectUnknown(String key,Object value){throw new IllegalArgumentException("Unsupported recommendation field");}
}
