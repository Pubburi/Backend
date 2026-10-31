package com.pubburi.pub.model.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pubburi.pub.controller.api.PageCriteria;
import com.pubburi.pub.controller.api.PageResponse;
import com.pubburi.pub.model.dao.UserDao;
import com.pubburi.pub.model.dto.User;

@Service
public class UserServiceImpl implements UserService {

	private final UserDao userDao;
	private final PasswordHasher passwordHasher;

	public UserServiceImpl(UserDao userDao, PasswordHasher passwordHasher) {
		this.userDao = userDao;
		this.passwordHasher = passwordHasher;
	}

	@Override
	public List<User> getUserList() {
		return userDao.selectAll().stream().map(User::withoutPass).toList();
	}

	@Override
	public PageResponse<User> getUserPage(PageCriteria criteria) {
		List<User> users = userDao.selectPaged(criteria.size(), criteria.offset()).stream().map(User::withoutPass)
				.toList();
		return PageResponse.of(users, criteria, userDao.countAll());
	}

	@Override
	@Transactional
	public int addUser(User user) {
		if (user == null || isBlank(user.getId()) || isBlank(user.getName()) || isBlank(user.getPass())) {
			return 0;
		}
		if (isUsedId(user.getId())) {
			return 0;
		}
		user.setPass(passwordHasher.hash(user.getPass()));
		if (isBlank(user.getRole())) {
			user.setRole("USER");
		}
		return userDao.insert(user);
	}

	@Override
	@Transactional
	public int modifyUser(User user) {
		if (user == null || isBlank(user.getId())) {
			return 0;
		}
		User existing = userDao.selectById(user.getId());
		if (existing == null) {
			return 0;
		}
		if (isBlank(user.getPass())) {
			user.setPass(existing.getPass());
		} else {
			user.setPass(passwordHasher.hash(user.getPass()));
		}
		if (isBlank(user.getRole())) {
			user.setRole(existing.getRole());
		}
		return userDao.update(user);
	}

	@Override
	@Transactional
	public int removeUser(String id) {
		if (isBlank(id)) {
			return 0;
		}
		return userDao.delete(id);
	}

	@Override
	@Transactional
	public int updateStamp(User user) {
		if (user == null || isBlank(user.getId())) {
			return 0;
		}
		return userDao.updateStamp(user);
	}

	@Override
	public User getUserById(String userId) {
		if (isBlank(userId)) {
			return null;
		}
		User user = userDao.selectById(userId);
		return user == null ? null : user.withoutPass();
	}

	@Override
	@Transactional
	public User login(User user) {
		if (user == null || isBlank(user.getId()) || isBlank(user.getPass())) {
			return null;
		}
		User stored = userDao.selectById(user.getId());
		if (stored == null || !passwordHasher.matches(user.getPass(), stored.getPass())) {
			return null;
		}
		if (passwordHasher.needsUpgrade(stored.getPass())) {
			userDao.updatePassword(stored.getId(), passwordHasher.hash(user.getPass()));
		}
		return stored.withoutPass();
	}

	@Override
	public boolean isUsedId(String id) {
		return !isBlank(id) && userDao.selectById(id) != null;
	}

	private boolean isBlank(String value) {
		return value == null || value.trim().isEmpty();
	}
}
