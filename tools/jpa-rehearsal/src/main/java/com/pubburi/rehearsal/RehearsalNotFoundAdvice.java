package com.pubburi.rehearsal;

import com.pubburi.pub.controller.api.*;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** An unregistered route is unavailable, not a successful stub or an internal persistence error. */
@RestControllerAdvice @Order(Ordered.HIGHEST_PRECEDENCE)
class RehearsalNotFoundAdvice {
    @ExceptionHandler({NoResourceFoundException.class,NoHandlerFoundException.class})
    ResponseEntity<ApiResponse<Void>> missing(Exception ignored){return ResponseEntity.status(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON).body(ApiResponse.error(ApiError.of("NOT_FOUND","Resource not found")));}
}
