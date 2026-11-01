package com.pubburi.pub.controller.rest;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.pubburi.pub.controller.api.ApiResponse;
import com.pubburi.pub.controller.response.DefaultRecommendationResponse;
import com.pubburi.pub.model.service.DefaultRecommendationService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;

@RestController
public class DefaultRecommendationController {
	private final DefaultRecommendationService service;
	public DefaultRecommendationController(DefaultRecommendationService service) { this.service = service; }
	@GetMapping(value = "/api/recommendations/default", produces = MediaType.APPLICATION_JSON_VALUE)
	@Operation(summary = "Read a public non-personal default selection", description = "First 1000 current rows by ID; remote B0 or current-DB fallback. No stored preferences or sales-state claim.")
	public ApiResponse<DefaultRecommendationResponse> defaults(
			@Parameter(schema = @Schema(types = {"integer"}, minimum = "1", maximum = "12", defaultValue = "4"))
			@RequestParam(defaultValue = "4") String size) {
		final int limit;
		try { limit = Integer.parseInt(size); }
		catch (NumberFormatException invalid) { throw new IllegalArgumentException("Size must be an integer between 1 and 12"); }
		return ApiResponse.ok(service.getDefaults(limit));
	}
}
