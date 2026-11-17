package com.pubburi.pub.controller.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

public record CatalogProductResponse(int id, String name, String type, int price, String img, float abv, int orderCount,
		@JsonInclude(JsonInclude.Include.ALWAYS)
		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = {"integer", "null"}, nullable = true,
				minimum = "1", maximum = "1000000", description = "Reviewed volume in milliliters when available")
		Integer volumeMl) {
}
