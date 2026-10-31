package com.pubburi.pub.controller.response;

import java.util.List;

public record ProfileResponse(UserResponse user, List<OrderResponse> orders, GradeResponse grade) {
}
