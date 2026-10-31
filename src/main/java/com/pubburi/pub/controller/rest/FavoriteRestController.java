package com.pubburi.pub.controller.rest;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.pubburi.pub.controller.api.ApiResponse;
import com.pubburi.pub.controller.api.PageCriteria;
import com.pubburi.pub.controller.api.PageResponse;
import com.pubburi.pub.controller.auth.AccessGuard;
import com.pubburi.pub.controller.auth.SessionUser;
import com.pubburi.pub.controller.response.FavoriteMembershipResponse;
import com.pubburi.pub.controller.response.ProductResponse;
import com.pubburi.pub.controller.response.ResponseMapper;
import com.pubburi.pub.model.dto.Product;
import com.pubburi.pub.model.service.FavoriteService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpSession;

@RestController
@RequestMapping(value = "/api/users/me/favorites", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Private account favorites")
public class FavoriteRestController {
	private final FavoriteService service;
	private final AccessGuard guard;

	public FavoriteRestController(FavoriteService service, AccessGuard guard) {
		this.service = service;
		this.guard = guard;
	}

	@GetMapping
	@Operation(summary = "Read the current account's favorites", description = "Private even for administrators. Current product fields, latest-added first. Empty page differs from expired account (401).")
	public ResponseEntity<ApiResponse<PageResponse<ProductResponse>>> page(
			@Parameter(schema = @Schema(types = {"integer"}, defaultValue = "1")) @RequestParam(required = false) String page,
			@Parameter(schema = @Schema(types = {"integer"}, defaultValue = "20")) @RequestParam(required = false) String size,
			HttpSession session) {
		SessionUser owner = guard.requireLogin(session);
		PageCriteria criteria = PageCriteria.of(optionalInteger(page), optionalInteger(size), 20);
		PageResponse<Product> result = service.getOwnedPage(owner.id(), owner.authEpoch(), criteria);
		if (result == null) throw expired();
		return ResponseEntity.ok(ApiResponse.ok(result.map(ResponseMapper::product)));
	}

	@PutMapping("/{productId}")
	@Operation(summary = "Ensure a product is favorited", description = "No body. Idempotent 200; duplicate PUT preserves creation order. Missing product 404; no other-account selector.")
	public ResponseEntity<ApiResponse<FavoriteMembershipResponse>> put(
			@Parameter(schema = @Schema(types = {"integer"}, minimum = "1", maximum = "2147483647")) @PathVariable String productId,
			HttpSession session) {
		SessionUser owner = guard.requireLogin(session);
		return mutate(owner, positiveProductId(productId), true);
	}

	@DeleteMapping("/{productId}")
	@Operation(summary = "Ensure a product is not favorited", description = "No body. Idempotent 200 even for absent membership or deleted product. Does not recreate catalog data.")
	public ResponseEntity<ApiResponse<FavoriteMembershipResponse>> delete(
			@Parameter(schema = @Schema(types = {"integer"}, minimum = "1", maximum = "2147483647")) @PathVariable String productId,
			HttpSession session) {
		SessionUser owner = guard.requireLogin(session);
		return mutate(owner, positiveProductId(productId), false);
	}

	private ResponseEntity<ApiResponse<FavoriteMembershipResponse>> mutate(SessionUser owner, int id, boolean favorited) {
		FavoriteService.MutationResult result = service.setOwned(owner.id(), owner.authEpoch(), id, favorited);
		return switch (result) {
			case ACCOUNT_EXPIRED -> throw expired();
			case PRODUCT_MISSING -> throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found");
			case PRESENT -> ResponseEntity.ok(ApiResponse.ok(new FavoriteMembershipResponse(id, true)));
			case ABSENT -> ResponseEntity.ok(ApiResponse.ok(new FavoriteMembershipResponse(id, false)));
		};
	}

	private Integer optionalInteger(String value) {
		if (value == null || value.isBlank()) return null;
		try { return Integer.valueOf(value.strip()); }
		catch (NumberFormatException invalid) { throw new IllegalArgumentException("Numeric parameters must fit 32-bit integers"); }
	}
	private int positiveProductId(String value) {
		if (!value.matches("[0-9]+")) throw new IllegalArgumentException("Product ID must be a positive integer");
		Integer id = optionalInteger(value);
		if (id == null || id < 1) throw new IllegalArgumentException("Product ID must be a positive integer");
		return id;
	}
	private ResponseStatusException expired() {
		return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Account session is no longer current");
	}
}
