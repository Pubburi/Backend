package com.pubburi.pub.controller.request;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import jakarta.validation.constraints.Min;

public record OrderDetailRequest(
		@Min(1) @JsonDeserialize(using = StrictIntegerDeserializer.Primitive.class) int productId,
		@Min(1) @JsonDeserialize(using = StrictIntegerDeserializer.Primitive.class) int quantity) {
}
