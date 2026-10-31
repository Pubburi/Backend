package com.pubburi.pub.controller.auth;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import com.pubburi.pub.model.dao.UserDao;
import com.pubburi.pub.model.dto.User;
import com.pubburi.pub.model.dto.UserUpdateResult;

import jakarta.servlet.http.HttpSession;

@Component
public class AccessGuard {
	public static final String SESSION_USER = "PUBBURI_USER";
	private final UserDao userDao;

	public AccessGuard(UserDao userDao) {
		this.userDao = userDao;
	}

	public SessionUser currentUser(HttpSession session) {
		Object value = value(session);
		if (!(value instanceof SessionUser observed)) {
			return null;
		}
		if (observed.id() == null || observed.authEpoch() == null || observed.authEpoch().isBlank()) {
			clearIfCurrent(session, observed);
			return null;
		}
		// A lookup failure propagates; never authorize using the stored role or erase
		// an otherwise valid session merely because the database is unavailable.
		User current = userDao.selectSessionIdentity(observed.id(), observed.authEpoch());
		if (!sameIdentity(value(session), observed)) {
			return null;
		}
		if (current == null) {
			clearIfCurrent(session, observed);
			return null;
		}
		// Do not overwrite a newer login/session while a DB lookup is in flight.
		return SessionUser.from(current);
	}

	private Object value(HttpSession session) {
		try {
			return session == null ? null : session.getAttribute(SESSION_USER);
		} catch (IllegalStateException invalidated) {
			return null;
		}
	}

	private boolean sameIdentity(Object value, SessionUser observed) {
		return value instanceof SessionUser live
				&& java.util.Objects.equals(live.id(), observed.id())
				&& java.util.Objects.equals(live.authEpoch(), observed.authEpoch());
	}

	public void establishSession(HttpSession session, User user) {
		synchronized (session) {
			session.setAttribute(SESSION_USER, SessionUser.from(user));
		}
	}

	public void refreshAfterCredentialChange(HttpSession session, SessionUser observed, UserUpdateResult updated) {
		if (!updated.credentialsChanged()) {
			return;
		}
		try {
			synchronized (session) {
				if (sameIdentity(value(session), observed)) {
					if (!java.util.Objects.equals(updated.id(), observed.id())
							|| !java.util.Objects.equals(updated.previousAuthEpoch(), observed.authEpoch())) {
						session.removeAttribute(SESSION_USER);
					} else {
						session.setAttribute(SESSION_USER, new SessionUser(observed.id(), observed.name(),
								observed.stamps(), observed.role(), updated.authEpoch()));
					}
				}
			}
		} catch (IllegalStateException invalidated) {
			// A concurrent logout must not restore the previous identity.
		}
	}

	private void clearIfCurrent(HttpSession session, SessionUser observed) {
		if (session == null) {
			return;
		}
		try {
			synchronized (session) {
				if (sameIdentity(value(session), observed)) {
					session.removeAttribute(SESSION_USER);
				}
			}
		} catch (IllegalStateException invalidated) {
			// A concurrent logout already removed the identity.
		}
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
		if (!user.admin() && (userId == null || !userId.equals(user.id()))) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not allowed");
		}
		return user;
	}
}
