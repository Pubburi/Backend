package com.pubburi.rehearsal;

import com.pubburi.pub.controller.api.ApiResponse;
import com.pubburi.pub.controller.auth.*;
import com.pubburi.pub.controller.response.CartRecommendationResponse;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import jakarta.servlet.http.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class CartRecommendationController {
    private static final String ACTOR=CartRecommendationController.class.getName()+".owner";
    private final AccessGuard guard;private final CartRecommendationService recommendations;
    public CartRecommendationController(AccessGuard guard,CartRecommendationService recommendations){this.guard=guard;this.recommendations=recommendations;}
    @ModelAttribute void authenticated(HttpServletRequest request,HttpSession session){
        var owner=guard.requireLogin(session);if(!FeedbackOwner.confirmed(request,owner,NotebookController.OWNER_HEADER))throw new IllegalArgumentException("Invalid cart recommendation owner");request.setAttribute(ACTOR,owner);
    }
    @PostMapping(value="/api/users/me/cart/recommendations",consumes=MediaType.APPLICATION_JSON_VALUE,produces=MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary="Read reviewed alternatives from this account's exact current cart; quantity does not weight similarity",parameters=@Parameter(name=NotebookController.OWNER_HEADER,in=ParameterIn.HEADER,required=true,description=FeedbackOwner.DESCRIPTION))
    public ApiResponse<CartRecommendationResponse> recommend(@RequestBody CartInput.Recommendation input,HttpServletRequest request,HttpSession session){
        if(!request.getParameterMap().isEmpty())throw new IllegalArgumentException("Invalid cart recommendation query");
        var owner=(SessionUser)request.getAttribute(ACTOR);var result=recommendations.recommend(owner,input);var actual=guard.requireLogin(session);
        if(!actual.id().equals(owner.id()) || !actual.authEpoch().equals(owner.authEpoch()))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Account session is no longer current");
        return ApiResponse.ok(result);
    }
}
