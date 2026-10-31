package com.pubburi.pub.controller.auth;

import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.function.BiFunction;

import com.pubburi.pub.model.dao.UserDao;
import com.pubburi.pub.model.dto.User;

public final class SessionFixtures {
	private SessionFixtures() { }

	public static User user(String id, String role) {
		User user = new User(id, id, null, 0, role);
		user.setAuthEpoch("fixture-" + id);
		return user;
	}

	public static AccessGuard guard(User... users) {
		return new AccessGuard(dao((id, epoch) -> Arrays.stream(users)
				.filter(user -> user.getId().equals(id) && user.getAuthEpoch().equals(epoch))
				.findFirst().orElse(null)));
	}

	public static UserDao dao(BiFunction<String, String, User> lookup) {
		return (UserDao) Proxy.newProxyInstance(UserDao.class.getClassLoader(), new Class<?>[] {UserDao.class},
				(proxy, method, args) -> {
					if (method.getName().equals("selectSessionIdentity")) {
						return lookup.apply((String) args[0], (String) args[1]);
					}
					throw new UnsupportedOperationException(method.getName());
				});
	}
}
