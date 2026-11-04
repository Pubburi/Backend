package com.pubburi.pub.controller.rest;

import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import com.pubburi.pub.controller.api.ApiResponse;
import com.pubburi.pub.controller.auth.AccessGuard;
import com.pubburi.pub.controller.request.ContentRecommendationRequest;
import com.pubburi.pub.controller.response.ContentRecommendationResponse;
import com.pubburi.pub.model.service.OwnedContentRecommendationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;

@RestController
public class OwnedContentRecommendationController {
    private final AccessGuard guard;private final OwnedContentRecommendationService service;
    public OwnedContentRecommendationController(AccessGuard guard,OwnedContentRecommendationService service){this.guard=guard;this.service=service;}
    @ModelAttribute public void privateResponse(HttpServletResponse response){response.setHeader("Cache-Control","private, no-store");response.setHeader("Vary","Cookie");}
    @PostMapping(value="/api/users/me/recommendations/content",consumes=MediaType.APPLICATION_JSON_VALUE,produces=MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary="Use the current account's stored preferences with only an explicitly checked DEMO catalog",
        description="Limit only; no owner, profile or catalog override. Current RR preferences and mapped product/features are checked before and after bounded B1 I/O. Changed inputs409, expired sessions401. REVIEWED_DEMO never means service SKU/rights approval. UNAVAILABLE has null result and empty products; no weaker local recommendation. This read performs no business write.")
    public ApiResponse<ContentRecommendationResponse> recommend(@Valid @RequestBody ContentRecommendationRequest input,
            @Parameter(hidden=true) @RequestParam Map<String,String> query,HttpSession session){
        var observed=guard.requireLogin(session);if(!query.isEmpty())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Query parameters are not supported");
        var result=service.recommend(observed.id(),observed.authEpoch(),input.getLimit());var current=guard.requireLogin(session);
        if(!Objects.equals(observed.id(),current.id()) || !Objects.equals(observed.authEpoch(),current.authEpoch()))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Account session is no longer current");
        return ApiResponse.ok(result);
    }
}
