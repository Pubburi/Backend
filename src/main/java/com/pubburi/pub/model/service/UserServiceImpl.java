package com.pubburi.pub.model.service;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pubburi.pub.controller.api.PageCriteria;
import com.pubburi.pub.controller.api.PageResponse;
import com.pubburi.pub.model.dao.UserDao;
import com.pubburi.pub.model.dto.User;
import com.pubburi.pub.model.dto.UserUpdate;
import com.pubburi.pub.model.dto.UserUpdateResult;

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
		user.setAuthEpoch(UUID.randomUUID().toString());
		if (isBlank(user.getRole())) {
			user.setRole("USER");
		}
		return userDao.insert(user);
	}

	@Override
	@Transactional
	public UserUpdateResult modifyUser(String id, UserUpdate changes) {
		if (isBlank(id) || changes == null) {
			return null;
		}
		User existing = userDao.selectByIdForUpdate(id);
		if (existing == null) {
			return null;
		}
		User user = new User();
		user.setId(id);
		user.setName(changes.name() == null ? existing.getName() : changes.name());
		user.setStamps(changes.stamps() == null ? existing.getStamps() : changes.stamps());
		user.setRole(isBlank(changes.role()) ? existing.getRole() : changes.role());
		if (isBlank(changes.pass())) {
			user.setPass(existing.getPass());
			user.setAuthEpoch(existing.getAuthEpoch());
		} else {
			user.setPass(passwordHasher.hash(changes.pass()));
			user.setAuthEpoch(UUID.randomUUID().toString());
		}
		if (userDao.update(user) != 1) {
			throw new IllegalStateException("User could not be updated");
		}
		return new UserUpdateResult(id, existing.getAuthEpoch(), user.getAuthEpoch(), !isBlank(changes.pass()));
	}

	@Override
	@Transactional
	public boolean modifyOwnProfile(String id, String authEpoch, String name) {
		if (name == null || name.isBlank() || name.length() > 100) {
			throw new IllegalArgumentException("Invalid profile name");
		}
		User existing = lockOwned(id, authEpoch);
		if (existing == null) return false;
		User changed = copy(existing);
		changed.setName(name);
		updateLocked(changed);
		return true;
	}

	@Override
	@Transactional
	public UserUpdateResult modifyOwnPassword(String id, String authEpoch, String currentPassword, String newPassword) {
		if (currentPassword == null || currentPassword.isBlank() || currentPassword.length() > 100 || !validPassword(newPassword)) {
			throw new IllegalArgumentException("Invalid password input");
		}
		User existing = lockOwned(id, authEpoch);
		if (existing == null) return null;
		if (!passwordHasher.matches(currentPassword, existing.getPass())) throw new CurrentPasswordMismatchException();
		User changed = copy(existing);
		changed.setPass(passwordHasher.hash(newPassword));
		changed.setAuthEpoch(UUID.randomUUID().toString());
		updateLocked(changed);
		return new UserUpdateResult(existing.getId(), existing.getAuthEpoch(), changed.getAuthEpoch(), true);
	}

	private User lockOwned(String id, String authEpoch) {
		if (isBlank(id) || isBlank(authEpoch)) return null;
		User locked = userDao.selectByIdForUpdate(id);
		return locked != null && java.util.Objects.equals(authEpoch, locked.getAuthEpoch()) ? locked : null;
	}

	private User copy(User user) {
		User result = new User(user.getId(), user.getName(), user.getPass(), user.getStamps(), user.getRole());
		result.setAuthEpoch(user.getAuthEpoch());
		return result;
	}

	private boolean validPassword(String value) {
		return value != null && !value.isBlank() && value.length() >= 4 && value.length() <= 100
				&& value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 72;
	}

	private void updateLocked(User user) {
		if (userDao.update(user) != 1) throw new IllegalStateException("User could not be updated");
	}

	@Override
	@Transactional
	public int removeUser(String id) {
		if (isBlank(id) || userDao.selectByIdForUpdate(id) == null) {
			return 0;
		}
		// Block new order FK references until notes are cleared and the user is deleted.
		userDao.clearOrderNotes(id);
		if (userDao.delete(id) != 1) {
			throw new IllegalStateException("User could not be deleted");
		}
		return 1;
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
			int upgraded = userDao.upgradePassword(stored.getId(), stored.getPass(), stored.getAuthEpoch(),
					passwordHasher.hash(user.getPass()));
			if (upgraded != 1) {
				// A concurrent reset/recreation must not be overwritten by an old hash upgrade.
				stored = userDao.selectByIdForUpdate(user.getId());
				if (stored == null || !passwordHasher.matches(user.getPass(), stored.getPass())) {
					return null;
				}
			}
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
