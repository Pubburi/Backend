package com.pubburi.pub.controller.rest;

import com.pubburi.pub.controller.api.ApiResponse;
import com.pubburi.pub.controller.response.ProductThemesResponse;
import com.pubburi.pub.model.service.ProductThemeService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ProductThemesController {
    private final ProductThemeService themes;

    public ProductThemesController(ProductThemeService themes) {
        this.themes = themes;
    }

    @GetMapping(value = "/api/products/themes", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Browse public reviewed product themes by alcohol percentage and volume",
        description = "Products come from the current catalog and require recorded volume and a source. BEGINNER is at most 7 vol%, GIFT is 300 to 500 mL, and TABLE is at least 500 mL. Each theme returns up to six products. An empty catalog result is UNAVAILABLE.")
    public ApiResponse<ProductThemesResponse> list(HttpServletRequest request, HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        if (request.getQueryString() != null) throw new IllegalArgumentException("Theme query parameters are not supported");
        return ApiResponse.ok(themes.list());
    }
}
