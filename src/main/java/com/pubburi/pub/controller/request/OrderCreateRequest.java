package com.pubburi.pub.controller.request;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record OrderCreateRequest(
		@Size(max = 100) String userId,
		@Pattern(regexp = "ONLINE|OFFLINE|TAKEOUT", message = "orderType must be ONLINE, OFFLINE, or TAKEOUT") String orderType,
		@Size(max = 20) String orderTable,
		@Valid @NotEmpty List<OrderDetailRequest> details) {
}
