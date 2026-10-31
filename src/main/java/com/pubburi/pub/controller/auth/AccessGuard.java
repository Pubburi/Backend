package com.pubburi.pub.controller.auth;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import jakarta.servlet.http.HttpSession;

@Component
public class AccessGuard {
	public static final String SESSION_USER = "PUBBURI_USER";

	public SessionUser currentUser(HttpSession session) {
		Object value = session == null ? null : session.getAttribute(SESSION_USER);
		return value instanceof SessionUser user ? user : null;
	}

	public SessionUser requireLogin(HttpSession session) {
		SessionUser user = currentUser(session);
		if (user == null) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Login required");
		}
		return user;
	}

	public SessionUser requireAdmin(HttpSession session) {
		SessionUser user = requireLogin(session);
		if (!user.admin()) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Admin permission required");
		}
		return user;
	}

	public SessionUser requireSelfOrAdmin(HttpSession session, String userId) {
		SessionUser user = requireLogin(session);
		if (!user.admin() && !user.id().equals(userId)) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not allowed");
		}
		return user;
	}
}
