package com.pubburi.pub.controller.auth;

import com.pubburi.pub.model.dto.User;

public record SessionUser(String id, String name, int stamps, String role) {
	public static SessionUser from(User user) {
		return new SessionUser(user.getId(), user.getName(), user.getStamps(), user.getRole());
	}

	public boolean admin() {
		return "ADMIN".equalsIgnoreCase(role);
	}
}
