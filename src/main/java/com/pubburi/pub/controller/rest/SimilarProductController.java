package com.pubburi.pub.controller.rest;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.pubburi.pub.controller.api.ApiResponse;
import com.pubburi.pub.controller.response.SimilarProductsResponse;
import com.pubburi.pub.model.service.SimilarProductService;

@RestController
public class SimilarProductController {
    private final SimilarProductService service;

    public SimilarProductController(SimilarProductService service) {
        this.service = service;
    }

    @GetMapping(value = "/api/products/{id}/similar", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<SimilarProductsResponse>> similar(@PathVariable int id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(service.similar(id)));
    }
}
