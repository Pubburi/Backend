package com.pubburi.pub.controller.response;

public record CommentResponse(int id, String userId, int productId, float rating, String comment) {
}
