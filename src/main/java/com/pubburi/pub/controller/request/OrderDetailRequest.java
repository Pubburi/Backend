package com.pubburi.pub.controller.request;

import jakarta.validation.constraints.Min;

public record OrderDetailRequest(@Min(1) int productId, @Min(1) int quantity) {
}
