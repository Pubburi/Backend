package com.pubburi.pub.controller.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pubburi.pub.controller.response.ResponseMapper;
import com.pubburi.pub.model.dto.User;
import com.pubburi.pub.model.dto.UserUpdateResult;

class AccessGuardTests {
	@Test
	void oldAdminSnapshotCannotOverrideCurrentDatabaseRoleAndProfile() {
		User user = SessionFixtures.user("member", "ADMIN");
		AccessGuard guard = SessionFixtures.guard(user);
		MockHttpSession session = signedIn(user);
		user.setRole("USER");
		user.setName("updated");
		user.setStamps(12);
		assertThatThrownBy(() -> guard.requireAdmin(session)).isInstanceOfSatisfying(ResponseStatusException.class,
				error -> assertThat(error.getStatusCode().value()).isEqualTo(403));
		SessionUser current = guard.requireLogin(session);
		assertThat(current.role()).isEqualTo("USER");
		assertThat(current.name()).isEqualTo("updated");
		assertThat(current.stamps()).isEqualTo(12);
	}

	@Test
	void deletedAccountIsRejectedAndItsOldIdentityIsRemoved() {
		User user = SessionFixtures.user("member", "USER");
		MockHttpSession session = signedIn(user);
		AccessGuard guard = new AccessGuard(SessionFixtures.dao((id, epoch) -> null));
		assertUnauthorized(guard, session);
		assertThat(session.getAttribute(AccessGuard.SESSION_USER)).isNull();
	}

	@Test
	void dormantSessionCannotBindToARecreatedAccountWithTheSameId() {
		User old = SessionFixtures.user("reused", "USER");
		MockHttpSession dormant = signedIn(old);
		User replacement = SessionFixtures.user("reused", "USER");
		replacement.setAuthEpoch("new-account-epoch");
		AccessGuard guard = SessionFixtures.guard(replacement);
		assertUnauthorized(guard, dormant);
		assertThat(guard.requireLogin(signedIn(replacement)).id()).isEqualTo("reused");
	}

	@Test
	void credentialRotationRejectsOtherSessionsAndRefreshesOnlyTheActingSession() {
		User user = SessionFixtures.user("admin", "ADMIN");
		MockHttpSession actor = signedIn(user), other = signedIn(user);
		SessionUser observed = (SessionUser) actor.getAttribute(AccessGuard.SESSION_USER);
		user.setAuthEpoch("after-password-change");
		AccessGuard guard = SessionFixtures.guard(user);
		guard.refreshAfterCredentialChange(actor, observed,
				new UserUpdateResult(user.getId(), observed.authEpoch(), user.getAuthEpoch(), true));
		assertThat(guard.requireAdmin(actor).authEpoch()).isEqualTo("after-password-change");
		assertUnauthorized(guard, other);
	}

	@Test
	void laterCredentialResetCannotBeAdoptedByAnEarlierSelfUpdateResult() {
		User user = SessionFixtures.user("admin", "ADMIN");
		MockHttpSession session = signedIn(user);
		SessionUser observed = (SessionUser) session.getAttribute(AccessGuard.SESSION_USER);
		UserUpdateResult result = new UserUpdateResult(user.getId(), user.getAuthEpoch(), "own-update-epoch", true);
		user.setAuthEpoch("later-reset-epoch");
		AccessGuard guard = SessionFixtures.guard(user);
		guard.refreshAfterCredentialChange(session, observed, result);
		assertThat(((SessionUser) session.getAttribute(AccessGuard.SESSION_USER)).authEpoch()).isEqualTo("own-update-epoch");
		assertUnauthorized(guard, session);
	}

	@Test
	void credentialResetBeforeTheUpdateLockCannotRefreshARevokedSession() {
		User user = SessionFixtures.user("admin", "ADMIN");
		MockHttpSession session = signedIn(user);
		SessionUser observed = (SessionUser) session.getAttribute(AccessGuard.SESSION_USER);
		user.setAuthEpoch("epoch-applied-by-this-update");
		AccessGuard guard = SessionFixtures.guard(user);
		guard.refreshAfterCredentialChange(session, observed,
				new UserUpdateResult(user.getId(), "another-admin-reset-before-lock", user.getAuthEpoch(), true));
		assertUnauthorized(guard, session);
	}

	@Test
	void ordinaryProfileUpdatesNeverRefreshTheSessionEpoch() {
		User user = SessionFixtures.user("admin", "ADMIN");
		MockHttpSession session = signedIn(user);
		SessionUser observed = (SessionUser) session.getAttribute(AccessGuard.SESSION_USER);
		user.setAuthEpoch("reset-before-profile-lock");
		AccessGuard guard = SessionFixtures.guard(user);
		guard.refreshAfterCredentialChange(session, observed,
				new UserUpdateResult(user.getId(), user.getAuthEpoch(), user.getAuthEpoch(), false));
		assertThat(((SessionUser) session.getAttribute(AccessGuard.SESSION_USER)).authEpoch()).isEqualTo(observed.authEpoch());
		assertUnauthorized(guard, session);
	}

	@Test
	void legacySessionWithoutAccountEpochRequiresLoginWithoutQueryingTheDatabase() {
		AtomicInteger calls = new AtomicInteger();
		AccessGuard guard = new AccessGuard(SessionFixtures.dao((id, epoch) -> { calls.incrementAndGet(); return null; }));
		MockHttpSession session = new MockHttpSession();
		session.setAttribute(AccessGuard.SESSION_USER, new SessionUser("old", "old", 0, "ADMIN", null));
		assertUnauthorized(guard, session);
		assertThat(calls).hasValue(0);
	}

	@Test
	void databaseFailureDoesNotAuthorizeOrEraseAValidSession() {
		User user = SessionFixtures.user("member", "USER");
		MockHttpSession session = signedIn(user);
		Object original = session.getAttribute(AccessGuard.SESSION_USER);
		AccessGuard guard = new AccessGuard(SessionFixtures.dao((id, epoch) -> { throw new IllegalStateException("DB unavailable"); }));
		assertThatThrownBy(() -> guard.requireLogin(session)).isInstanceOf(IllegalStateException.class);
		assertThat(session.getAttribute(AccessGuard.SESSION_USER)).isSameAs(original);
	}

	@Test
	void aNewLoginDuringLookupIsPreservedAndTheOldRequestIsNotAuthorized() {
		User old = SessionFixtures.user("old", "USER"), replacement = SessionFixtures.user("new", "USER");
		MockHttpSession session = signedIn(old);
		AccessGuard guard = new AccessGuard(SessionFixtures.dao((id, epoch) -> {
			session.setAttribute(AccessGuard.SESSION_USER, SessionUser.from(replacement));
			return old;
		}));
		assertUnauthorized(guard, session);
		assertThat(((SessionUser) session.getAttribute(AccessGuard.SESSION_USER)).id()).isEqualTo("new");
		guard.refreshAfterCredentialChange(session, SessionUser.from(old),
				new UserUpdateResult(old.getId(), old.getAuthEpoch(), "another-epoch", true));
		assertThat(((SessionUser) session.getAttribute(AccessGuard.SESSION_USER)).id()).isEqualTo("new");
	}

	@Test
	void logoutDuringLookupCannotRestoreTheIdentity() {
		User user = SessionFixtures.user("member", "USER");
		MockHttpSession session = signedIn(user);
		AccessGuard guard = new AccessGuard(SessionFixtures.dao((id, epoch) -> { session.invalidate(); return user; }));
		assertUnauthorized(guard, session);
	}

	@Test
	void accountEpochIsNotPartOfUserJsonOrThePublicResponseContract() {
		User user = SessionFixtures.user("member", "USER");
		ObjectMapper mapper = new ObjectMapper();
		assertThat(mapper.valueToTree(user).has("authEpoch")).isFalse();
		assertThat(mapper.valueToTree(ResponseMapper.user(user)).has("authEpoch")).isFalse();
	}

	private static MockHttpSession signedIn(User user) {
		MockHttpSession session = new MockHttpSession();
		session.setAttribute(AccessGuard.SESSION_USER, SessionUser.from(user));
		return session;
	}

	private static void assertUnauthorized(AccessGuard guard, MockHttpSession session) {
		assertThatThrownBy(() -> guard.requireLogin(session)).isInstanceOfSatisfying(ResponseStatusException.class,
				error -> assertThat(error.getStatusCode().value()).isEqualTo(401));
	}
}
