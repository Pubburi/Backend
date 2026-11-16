package com.pubburi.pub.controller.rest;

import com.pubburi.pub.controller.api.*;
import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/** An explicit legacy capability response; ordinary 404/errors never authorize a device-cart fallback. */
@Hidden @RestController
public class UnavailableCartController {
    @GetMapping("/api/users/me/cart")
    public ResponseEntity<ApiResponse<Void>> unsupported(){return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error(ApiError.of("CART_NOT_SUPPORTED","Account cart is not available on this provider")));}
    @RequestMapping(value={"/api/users/me/cart","/api/users/me/cart/items/{productId}","/api/users/me/cart/merge","/api/users/me/cart/checkout","/api/users/me/cart/operations/{operationId}/resolve"},method={RequestMethod.POST,RequestMethod.PUT,RequestMethod.DELETE})
    public ResponseEntity<ApiResponse<Void>> unavailable(){return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error(ApiError.of("NOT_FOUND","Account cart is not available on this provider")));}
}
