package com.pubburi.pub.controller.response;

import java.util.Date;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

public record OrderResponse(int oId,
		@Schema(nullable = true, description = "삭제된 회원의 주문 소유자는 null; 관리자만 조회") String userId,
		String orderType, String orderTable, String completed,
		Date orderTime, List<OrderDetailResponse> details,
		@Schema(nullable = true, description = "상세가 남지 않은 과거 주문은 총액 미상") Integer totalPrice,
		String priceBasis) {
}
