package com.pubburi.pub.controller.response;

import java.util.List;

import com.pubburi.pub.model.dto.ProductFeatures;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

@Schema(requiredProperties = {"productId", "volumeMl", "description", "tasteTags", "aromaTags", "sourceName", "sourceUrl", "sourceNote"})
public record ProductFeaturesResponse(
		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1") int productId,
		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = {"integer", "null"}, nullable = true, minimum = "1", maximum = "1000000") Integer volumeMl,
		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = {"string", "null"}, nullable = true, maxLength = 2000) String description,
		@Schema(types = {"array", "null"}, nullable = true, description = "null=unknown; []=reviewed with no declared tags")
		@Size(max = 20) List<String> tasteTags,
		@Schema(types = {"array", "null"}, nullable = true, description = "null=unknown; []=reviewed with no declared tags")
		@Size(max = 20) List<String> aromaTags,
		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = {"string", "null"}, nullable = true, maxLength = 200) String sourceName,
		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = {"string", "null"}, nullable = true, maxLength = 1000) String sourceUrl,
		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = {"string", "null"}, nullable = true, maxLength = 1000) String sourceNote) {
	public static ProductFeaturesResponse from(ProductFeatures features) {
		return new ProductFeaturesResponse(features.getProductId(), features.getVolumeMl(), features.getDescription(),
				features.getTasteTags(), features.getAromaTags(), features.getSourceName(), features.getSourceUrl(),
				features.getSourceNote());
	}
}
