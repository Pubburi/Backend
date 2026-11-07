package com.pubburi.pub.controller.response;

import java.util.List;
import com.pubburi.pub.model.dto.ProductSuggestion;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(requiredProperties = {"items"})
public record ProductSuggestionsResponse(@ArraySchema(maxItems = 8) List<ProductSuggestion> items) { }
