package com.pubburi.pub.controller.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(
		@NotBlank @Size(max = 100) String id,
		@NotBlank @Size(max = 100) String pass) {
}
