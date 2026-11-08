package com.pubburi.rehearsal;

import com.pubburi.pub.controller.api.ApiResponse;
import com.pubburi.pub.controller.auth.*;
import com.pubburi.pub.controller.response.OrderRecommendationResponse;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import jakarta.servlet.http.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class OrderRecommendationController {
    private static final String ACTOR=OrderRecommendationController.class.getName()+".owner";
    private final AccessGuard guard;private final OrderRecommendationService recommendations;
    public OrderRecommendationController(AccessGuard guard,OrderRecommendationService recommendations){this.guard=guard;this.recommendations=recommendations;}
    @ModelAttribute void authenticated(HttpServletRequest request,HttpSession session){var owner=guard.requireLogin(session);if(!FeedbackOwner.confirmed(request,owner,NotebookController.OWNER_HEADER))throw new IllegalArgumentException("Invalid order recommendation owner");request.setAttribute(ACTOR,owner);}
    @PostMapping(value="/api/users/me/orders/{id}/recommendations",consumes=MediaType.APPLICATION_JSON_VALUE,produces=MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary="Read factual alternatives from this account's current demo order; historical prices and quantity do not weight similarity",parameters=@Parameter(name=NotebookController.OWNER_HEADER,in=ParameterIn.HEADER,required=true,description=FeedbackOwner.DESCRIPTION))
    public ApiResponse<OrderRecommendationResponse> recommend(@PathVariable int id,@RequestBody OrderRecommendationInput input,HttpServletRequest request,HttpSession session){
        if(!request.getParameterMap().isEmpty())throw new IllegalArgumentException("Invalid order recommendation query");
        var owner=(SessionUser)request.getAttribute(ACTOR);var result=recommendations.recommend(owner,id,input);var current=guard.requireLogin(session);
        if(!current.id().equals(owner.id()) || !current.authEpoch().equals(owner.authEpoch()))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Account session is no longer current");
        return ApiResponse.ok(result);
    }
}
