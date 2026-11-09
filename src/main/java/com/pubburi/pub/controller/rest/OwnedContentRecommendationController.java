package com.pubburi.pub.controller.rest;

import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import com.pubburi.pub.controller.api.ApiResponse;
import com.pubburi.pub.controller.auth.AccessGuard;
import com.pubburi.pub.controller.auth.FeedbackOwner;
import com.pubburi.pub.controller.auth.SessionUser;
import com.pubburi.pub.controller.request.ContentRecommendationRequest;
import com.pubburi.pub.controller.request.ExplorationRecommendationRequest;
import com.pubburi.pub.controller.response.ContentRecommendationResponse;
import com.pubburi.pub.controller.response.ExplorationRecommendationResponse;
import com.pubburi.pub.model.service.OwnedContentRecommendationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;

@RestController
public class OwnedContentRecommendationController {
    private final AccessGuard guard;private final OwnedContentRecommendationService service;
    public OwnedContentRecommendationController(AccessGuard guard,OwnedContentRecommendationService service){this.guard=guard;this.service=service;}
    @ModelAttribute public void privateResponse(HttpServletResponse response){response.setHeader("Cache-Control","private, no-store");response.setHeader("Vary","Cookie");}
    @PostMapping(value="/api/users/me/recommendations/content",consumes=MediaType.APPLICATION_JSON_VALUE,produces=MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary="Use the current account's stored preferences with an explicitly reviewed catalog",
        description="Optional includeActivity uses current own favorites and newest high-rated reviews only when stored categories are unknown; no owner, profile or catalog override. Current RR preferences, mapped product facts and original pins are checked before and after bounded B1 I/O. Changed inputs409, expired sessions401. REVIEWED_DEMO retains its original five-minute business-snapshot expiration; explicit REVIEWED_FAMILY_BROWSE_DEMO is browse-only with unknown volume. REVIEWED_LOCAL_CATALOG binds stable reviewed source facts to explicit current business IDs and the imported source UUID, then reads current demo prices and row existence; it does not verify retail prices, sale status or inventory. Deleted local bindings are unavailable candidates with unknown prices. UNAVAILABLE has null result and empty products; no weaker local recommendation. This read performs no business write. The optional feedback owner header applies stored choices to approved PostgreSQL container-DEMO or reviewed-local products, never family-only records.",
        parameters = @Parameter(name=FeedbackOwner.HEADER, in=ParameterIn.HEADER, description=FeedbackOwner.DESCRIPTION))
    public ApiResponse<ContentRecommendationResponse> recommend(@Valid @RequestBody ContentRecommendationRequest input,
            @Parameter(hidden=true) @RequestParam Map<String,String> query,HttpServletRequest request,HttpSession session){
        var observed=guard.requireLogin(session);if(!query.isEmpty())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Query parameters are not supported");
        var result=FeedbackOwner.confirmed(request, observed)
            ? service.recommend(observed.id(),observed.authEpoch(),input.getLimit(),input.getIncludeActivity(),true)
            : service.recommend(observed.id(),observed.authEpoch(),input.getLimit(),input.getIncludeActivity());
        requireCurrent(observed, session);
        return ApiResponse.ok(result);
    }

    @PostMapping(value = "/api/users/me/recommendations/exploration", consumes = MediaType.APPLICATION_JSON_VALUE,
        produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Explore reviewed alternatives using the current account's saved preferences",
        description = "Uses current saved hard constraints and excludes every known not-interested product group. Preserves the provider's positive-only selection order and factual explanations. Reads no activity or other account's preferences. Only reviewed container demo and reviewed local catalogs are supported. Input changes yield 409 and expired or replaced sessions yield 401. Provider failure yields UNAVAILABLE with null result and no products.",
        parameters = @Parameter(name = FeedbackOwner.HEADER, in = ParameterIn.HEADER, required = true,
            description = FeedbackOwner.DESCRIPTION))
    public ApiResponse<ExplorationRecommendationResponse> explore(@RequestBody ExplorationRecommendationRequest input,
            HttpServletRequest request, HttpSession session) {
        var observed = guard.requireLogin(session);
        if (request.getQueryString() != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Query parameters are not supported");
        }
        if (!FeedbackOwner.confirmed(request, observed)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Expected account header is required");
        }
        var result = service.explore(observed.id(), observed.authEpoch(), input.limit());
        requireCurrent(observed, session);
        return ApiResponse.ok(result);
    }

    private void requireCurrent(SessionUser observed, HttpSession session) {
        var current = guard.requireLogin(session);
        if (!Objects.equals(observed.id(), current.id()) || !Objects.equals(observed.authEpoch(), current.authEpoch())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Account session is no longer current");
        }
    }
}
