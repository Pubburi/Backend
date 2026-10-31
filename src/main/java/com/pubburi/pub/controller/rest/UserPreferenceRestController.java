package com.pubburi.pub.controller.rest;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.pubburi.pub.controller.api.ApiResponse;
import com.pubburi.pub.controller.auth.AccessGuard;
import com.pubburi.pub.controller.auth.SessionUser;
import com.pubburi.pub.controller.request.UserPreferencesRequest;
import com.pubburi.pub.controller.response.UserPreferencesResponse;
import com.pubburi.pub.model.dto.UserPreferences;
import com.pubburi.pub.model.service.UserPreferenceService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;

@RestController
@RequestMapping(value = "/api/users/me/preferences", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Private explicit preferences")
public class UserPreferenceRestController {
	private final UserPreferenceService service;
	private final AccessGuard guard;

	public UserPreferenceRestController(UserPreferenceService service, AccessGuard guard) {
		this.service = service;
		this.guard = guard;
	}

	@GetMapping
	@Operation(summary = "Read only the current account's explicit preferences", description = "Private even for administrators. An active account without preferences is NOT_STARTED; expired identities return 401.")
	public ResponseEntity<ApiResponse<UserPreferencesResponse>> get(HttpSession session) {
		SessionUser current = guard.requireLogin(session);
		return respond(service.getOwned(current.id(), current.authEpoch()));
	}

	@PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
	@Operation(summary = "Replace only the current account's explicit preferences", description = "Full replacement. Omitted fields are unknown. Soft targets never become hard filters; skip/reset requires all values unknown. No inferred profile or model learning.")
	public ResponseEntity<ApiResponse<UserPreferencesResponse>> replace(@Valid @RequestBody UserPreferencesRequest request,
			HttpSession session) {
		SessionUser current = guard.requireLogin(session);
		return respond(service.replaceOwned(current.id(), current.authEpoch(), request.toPreferences()));
	}

	private ResponseEntity<ApiResponse<UserPreferencesResponse>> respond(UserPreferences preferences) {
		if (preferences == null) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Account session is no longer current");
		}
		return ResponseEntity.ok(ApiResponse.ok(UserPreferencesResponse.from(preferences)));
	}
}
