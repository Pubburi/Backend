package com.pubburi.pub.controller.response;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(requiredProperties = {"items", "source"})
public record DefaultRecommendationResponse(List<ProductResponse> items, Source source) {
	public enum Source { BASELINE, FALLBACK }
}
