package com.pubburi.pub.controller.request;

import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public final class SearchPhraseRequest {
    private List<String> searchOptInFields = List.of();
    private Integer limit = 5;

    @NotNull @Size(max = 4)
    @Schema(defaultValue = "[]", description = "Separate explicit search choices; never a replacement preference snapshot")
    public List<String> getSearchOptInFields() { return searchOptInFields; }
    @JsonDeserialize(contentUsing = StrictStringDeserializer.class)
    public void setSearchOptInFields(List<String> fields) { searchOptInFields = fields; }
    @NotNull @Min(1) @Max(5) @Schema(defaultValue = "5")
    public Integer getLimit() { return limit; }
    @JsonDeserialize(using = StrictIntegerDeserializer.class)
    public void setLimit(Integer value) { limit = value; }
    @JsonIgnore @Schema(hidden = true)
    @AssertTrue(message = "Search choices must be unique supported fields")
    public boolean isSelectionValid() {
        return searchOptInFields == null || searchOptInFields.stream().allMatch(value -> value != null &&
                Set.of("categories", "tasteTags", "aromaTags", "targetAbvPercent").contains(value))
                && searchOptInFields.stream().distinct().count() == searchOptInFields.size();
    }
    @JsonAnySetter public void rejectUnknown(String field, Object value) {
        throw new IllegalArgumentException("Unsupported search phrase field");
    }
}
