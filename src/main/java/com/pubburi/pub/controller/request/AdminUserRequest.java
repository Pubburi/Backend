package com.pubburi.pub.controller.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record AdminUserRequest(
		@Size(max = 100) String name,
		@Size(min = 4, max = 100) String pass,
		@Min(0) Integer stamps,
		@Pattern(regexp = "USER|ADMIN", message = "role must be USER or ADMIN") String role) {
}
