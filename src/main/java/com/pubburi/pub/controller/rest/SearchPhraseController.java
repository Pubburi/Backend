package com.pubburi.pub.controller.rest;

import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import com.pubburi.pub.controller.api.ApiResponse;
import com.pubburi.pub.controller.auth.AccessGuard;
import com.pubburi.pub.controller.request.SearchPhraseRequest;
import com.pubburi.pub.controller.response.SearchPhraseResponse;
import com.pubburi.pub.model.service.SearchPhraseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;

@RestController
public class SearchPhraseController {
    private final AccessGuard guard;
    private final SearchPhraseService service;
    public SearchPhraseController(AccessGuard guard, SearchPhraseService service) { this.guard = guard; this.service = service; }
    @ModelAttribute
    public void privateResponse(HttpServletResponse response) {
        response.setHeader("Cache-Control", "private, no-store"); response.setHeader("Vary", "Cookie");
    }
    @PostMapping(value = "/api/users/me/search-phrases", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Suggest exact search conditions from only the current account's preferences",
            description = "Explicit search choices only. No account/preference override. Reads current owned preferences before and after bounded remote I/O. Changed preferences return409, expired sessions401. UNAVAILABLE never supplies a weaker query. Product counts remain unknown; no search or preference write is performed.")
    public ApiResponse<SearchPhraseResponse> suggest(@Valid @RequestBody SearchPhraseRequest input,
            @Parameter(hidden = true) @RequestParam Map<String, String> query, HttpSession session) {
        var observed = guard.requireLogin(session);
        if (!query.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Query parameters are not supported");
        var result = service.suggest(observed.id(), observed.authEpoch(), input.getSearchOptInFields(), input.getLimit());
        var current = guard.requireLogin(session);
        if (!Objects.equals(observed.id(), current.id()) || !Objects.equals(observed.authEpoch(), current.authEpoch()))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Account session is no longer current");
        return ApiResponse.ok(result);
    }
}
