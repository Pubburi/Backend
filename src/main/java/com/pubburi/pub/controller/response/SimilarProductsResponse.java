package com.pubburi.pub.controller.response;

import java.util.List;

public record SimilarProductsResponse(List<ProductResponse> items) {
    public SimilarProductsResponse {
        items = List.copyOf(items);
    }
}
