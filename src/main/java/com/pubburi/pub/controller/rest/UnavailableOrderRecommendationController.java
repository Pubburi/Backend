package com.pubburi.pub.controller.rest;

import com.pubburi.pub.controller.api.*;
import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/** Explicit legacy capability only; does not fabricate an order or recommendation. */
@Hidden @RestController
public class UnavailableOrderRecommendationController {
    @PostMapping("/api/users/me/orders/{id}/recommendations")
    public ResponseEntity<ApiResponse<Void>> unsupported(){return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error(ApiError.of("ORDER_CONTEXT_NOT_SUPPORTED","Order recommendations are not available on this provider")));}
}
