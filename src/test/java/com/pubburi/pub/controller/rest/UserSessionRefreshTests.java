package com.pubburi.pub.controller.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Proxy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.web.server.ResponseStatusException;

import com.pubburi.pub.controller.auth.AccessGuard;
import com.pubburi.pub.controller.auth.SessionFixtures;
import com.pubburi.pub.controller.auth.SessionUser;
import com.pubburi.pub.controller.request.AdminUserRequest;
import com.pubburi.pub.model.dto.User;
import com.pubburi.pub.model.dto.UserUpdateResult;
import com.pubburi.pub.model.service.UserService;

class UserSessionRefreshTests {
	@Test
	void selfUpdateUsesOnlyItsCapturedEpochAndNeverReadsAReplacementIdentity() {
		User account = SessionFixtures.user("admin", "ADMIN");
		String originalEpoch = account.getAuthEpoch();
		AccessGuard guard = SessionFixtures.guard(account);
		MockHttpSession session = new MockHttpSession();
		guard.establishSession(session, account);
		UserService service = (UserService) Proxy.newProxyInstance(UserService.class.getClassLoader(),
				new Class<?>[] {UserService.class}, (proxy, method, args) -> {
					if (!method.getName().equals("modifyUser")) {
						throw new AssertionError("Post-update identity must not be re-read: " + method.getName());
					}
					// A later reset happens after this update produced its immutable result.
					account.setAuthEpoch("later-reset");
					return new UserUpdateResult("admin", originalEpoch, "this-update", true);
				});
		UserRestController controller = new UserRestController(service, null, guard, null);
		assertThat(controller.updateUser("admin", new AdminUserRequest(null, "new pass", null, null), session)
				.getBody().data()).isTrue();
		assertThat(((SessionUser) session.getAttribute(AccessGuard.SESSION_USER)).authEpoch()).isEqualTo("this-update");
		assertThatThrownBy(() -> guard.requireLogin(session)).isInstanceOfSatisfying(ResponseStatusException.class,
				error -> assertThat(error.getStatusCode().value()).isEqualTo(401));
	}
}
