package com.pubburi.pub.controller.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(requiredProperties = {"productId", "favorited"})
public record FavoriteMembershipResponse(@Schema(minimum = "1", maximum = "2147483647") int productId, boolean favorited) { }
