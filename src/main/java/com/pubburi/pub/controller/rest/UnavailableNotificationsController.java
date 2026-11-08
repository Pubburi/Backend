package com.pubburi.pub.controller.rest;

import com.pubburi.pub.controller.api.*;
import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@Hidden @RestController
public class UnavailableNotificationsController {
    @GetMapping("/api/users/me/notifications")
    public ResponseEntity<ApiResponse<Void>> unsupported(){return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error(ApiError.of("NOTIFICATIONS_NOT_SUPPORTED","Notifications are not available on this provider")));}
    @RequestMapping(value={"/api/users/me/notifications","/api/users/me/notifications/read","/api/users/me/notifications/{id}/read"},method={RequestMethod.POST,RequestMethod.PUT,RequestMethod.DELETE})
    public ResponseEntity<ApiResponse<Void>> unavailable(){return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error(ApiError.of("NOT_FOUND","Notifications are not available on this provider")));}
}
