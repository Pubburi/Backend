package com.pubburi.pub.controller.response;

import io.swagger.v3.oas.annotations.media.Schema;

public record OrderDetailResponse(int id, int orderId,
		@Schema(nullable = true, description = "삭제된 상품의 참조는 null") Integer productId,
		int quantity, String name, int unitPrice,
		int sumPrice, String type, String img) {
}
