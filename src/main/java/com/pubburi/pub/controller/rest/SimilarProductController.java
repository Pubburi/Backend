package com.pubburi.pub.controller.rest;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Schema;

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
    @Operation(summary="Read current similar products with optional factual comparison reasons",parameters=@Parameter(name="includeExplanations",in=ParameterIn.QUERY,schema=@Schema(type="boolean",defaultValue="false"),description="One literal true or false; true returns an explanation for every returned product in the same order. No explanation is invented for empty or unavailable results."))
    public ResponseEntity<ApiResponse<SimilarProductsResponse>> similar(@PathVariable int id,HttpServletRequest request,HttpServletResponse response) {
        response.setHeader("Cache-Control","no-store");
        var values=request.getParameterValues("includeExplanations");
        if(request.getParameterMap().size()>(values==null?0:1) || values!=null && (values.length!=1 || !java.util.Set.of("true","false").contains(values[0])))
            throw new IllegalArgumentException("Only one literal includeExplanations query is allowed");
        boolean explained=values!=null && values[0].equals("true");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(explained?service.similar(id,true):service.similar(id)));
    }
}
