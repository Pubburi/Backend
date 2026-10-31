package com.pubburi.pub.controller.request;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CommentUpdateRequest(
		@DecimalMin("0.5") @DecimalMax("5.0") float rating,
		@NotBlank @Size(max = 200) String comment) {
}
