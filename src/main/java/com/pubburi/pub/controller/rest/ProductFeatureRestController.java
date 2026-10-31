package com.pubburi.pub.controller.rest;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.pubburi.pub.controller.api.ApiResponse;
import com.pubburi.pub.controller.auth.AccessGuard;
import com.pubburi.pub.controller.request.ProductFeaturesRequest;
import com.pubburi.pub.controller.response.ProductFeaturesResponse;
import com.pubburi.pub.model.dto.ProductFeatures;
import com.pubburi.pub.model.service.ProductFeatureService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;

@RestController
@RequestMapping(value = "/api", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Product features", description = "Source-recorded attributes; null means unknown, not zero or absent flavor")
public class ProductFeatureRestController {
	private final ProductFeatureService featureService;
	private final AccessGuard accessGuard;

	public ProductFeatureRestController(ProductFeatureService featureService, AccessGuard accessGuard) {
		this.featureService = featureService;
		this.accessGuard = accessGuard;
	}

	@GetMapping("/products/{id}/features")
	@Operation(summary = "Read product attributes", description = "Existing products without reviewed attributes return null fields. Missing products return 404.")
	public ResponseEntity<ApiResponse<ProductFeaturesResponse>> features(@PathVariable int id) {
		return respond(featureService.getProductFeatures(id));
	}

	@PutMapping(value = "/admin/products/{id}/features", consumes = MediaType.APPLICATION_JSON_VALUE)
	@Operation(summary = "Replace product attributes", description = "ADMIN only. Omitted fields become unknown. A non-null tag array, including [], requires sourceName. Source records are not usage-rights approval.")
	public ResponseEntity<ApiResponse<ProductFeaturesResponse>> replace(@PathVariable int id,
			@Valid @RequestBody ProductFeaturesRequest request, HttpSession session) {
		accessGuard.requireAdmin(session);
		return respond(featureService.replaceProductFeatures(id, request.toFeatures()));
	}

	private ResponseEntity<ApiResponse<ProductFeaturesResponse>> respond(ProductFeatures features) {
		if (features == null) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found");
		}
		return ResponseEntity.ok(ApiResponse.ok(ProductFeaturesResponse.from(features)));
	}
}
