package com.pubburi.pub.controller.response;

import java.util.Date;
import java.util.List;

public record OrderResponse(int oId, String userId, String orderType, String orderTable, String completed,
		Date orderTime, List<OrderDetailResponse> details) {
}
