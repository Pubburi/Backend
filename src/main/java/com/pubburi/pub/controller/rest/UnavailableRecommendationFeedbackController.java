package com.pubburi.pub.controller.rest;

import com.pubburi.pub.controller.api.ApiResponse;
import com.pubburi.pub.controller.api.ApiError;
import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/** The pre-cutover MySQL runtime has no feedback storage or writes. */
@Hidden
@RestController
public class UnavailableRecommendationFeedbackController {
    @RequestMapping(value = "/api/users/me/recommendations/feedback/{productId}",
        method = {RequestMethod.GET, RequestMethod.PUT, RequestMethod.DELETE})
    public ResponseEntity<ApiResponse<Void>> unavailable() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).header("Cache-Control", "private, no-store")
            .body(ApiResponse.error(ApiError.of("NOT_FOUND", "Recommendation feedback is not available")));
    }
}
