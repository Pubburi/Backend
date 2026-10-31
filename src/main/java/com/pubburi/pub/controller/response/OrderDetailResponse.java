package com.pubburi.pub.controller.response;

public record OrderDetailResponse(int id, int orderId, int productId, int quantity, String name, int unitPrice,
		int sumPrice, String type, String img) {
}
