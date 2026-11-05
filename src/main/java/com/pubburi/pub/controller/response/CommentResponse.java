package com.pubburi.pub.controller.response;

import java.util.List;
import com.fasterxml.jackson.annotation.JsonInclude;

public record CommentResponse(int id, String userId, int productId, float rating, String comment,
        @JsonInclude(JsonInclude.Include.NON_NULL) List<String> tasteTags) {
    public CommentResponse(int id, String userId, int productId, float rating, String comment) {
        this(id, userId, productId, rating, comment, null);
    }
}
