package com.pubburi.pub.controller.api;

public record ApiResponse<T>(T data, String message, ApiError error) {
	public static <T> ApiResponse<T> ok(T data) {
		return new ApiResponse<>(data, "ok", null);
	}

	public static <T> ApiResponse<T> message(T data, String message) {
		return new ApiResponse<>(data, message, null);
	}

	public static ApiResponse<Void> error(ApiError error) {
		return new ApiResponse<>(null, null, error);
	}
}
