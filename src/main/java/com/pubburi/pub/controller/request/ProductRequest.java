package com.pubburi.pub.controller.request;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ProductRequest(
		@NotBlank @Size(max = 100) String name,
		@NotBlank @Size(max = 20) String type,
		@Min(1) @JsonDeserialize(using = StrictIntegerDeserializer.Primitive.class) int price,
		@NotBlank @Size(max = 100) String img,
		@DecimalMin("0.0") @DecimalMax("100.0") float abv,
		@Min(0) @JsonDeserialize(using = StrictIntegerDeserializer.Primitive.class) int orderCount) {
}
