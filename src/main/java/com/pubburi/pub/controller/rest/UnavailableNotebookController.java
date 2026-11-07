package com.pubburi.pub.controller.rest;

import com.pubburi.pub.controller.api.*;
import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/** The explicitly retained MySQL baseline has no private notebook persistence. */
@Hidden
@RestController
public class UnavailableNotebookController {
    @RequestMapping(value={"/api/users/me/notebook","/api/users/me/notebook/{id}","/api/users/me/notebook/by-product/{productId}"},
        method={RequestMethod.GET,RequestMethod.POST,RequestMethod.PUT,RequestMethod.DELETE})
    public ResponseEntity<ApiResponse<Void>> unavailable(){
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error(ApiError.of("NOT_FOUND","Private notebook is not available")));
    }
}
