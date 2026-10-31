package com.pubburi.pub.controller.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record JoinUserRequest(
		@NotBlank @Size(max = 100) String id,
		@NotBlank @Size(max = 100) String name,
		@NotBlank @Size(min = 4, max = 100) String pass) {
}
