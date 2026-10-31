package com.pubburi.pub.model.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.pubburi.pub.model.dao.UserDao;
import com.pubburi.pub.model.dto.User;
import com.pubburi.pub.model.dto.UserUpdate;
import com.pubburi.pub.model.dto.UserUpdateResult;

class UserServiceImplTests {
	private final PasswordHasher passwordHasher = new PasswordHasher();
	private final FakeUserDao userDao = new FakeUserDao();
	private final UserServiceImpl userService = new UserServiceImpl(userDao, passwordHasher);

	@Test
	void addUserHashesPasswordAndDefaultsRole() {
		User user = new User("id01", "홍길동", "pass01", 0, null);

		int result = userService.addUser(user);

		assertThat(result).isEqualTo(1);
		assertThat(userDao.saved.getPass()).startsWith("$2");
		assertThat(userDao.saved.getRole()).isEqualTo("USER");
		assertThat(userDao.saved.getAuthEpoch()).isNotBlank();
	}

	@Test
	void loginMigratesLegacySha256Password() {
		User stored = new User("id01", "홍길동", passwordHasher.legacySha256("pass01"), 5, "USER");
		stored.setAuthEpoch("same-account");
		userDao.saved = stored;

		User result = userService.login(new User("id01", null, "pass01", 0, null));

		assertThat(result).isNotNull();
		assertThat(result.getPass()).isNull();
		assertThat(userDao.updatedPassword).startsWith("$2");
		assertThat(result.getAuthEpoch()).isEqualTo("same-account");
	}

	@Test
	void omittedProfileFieldsPreserveLockedRoleCredentialsAndEpoch() {
		User stored = new User("id01", "old name", "existing hash", 7, "USER");
		stored.setAuthEpoch("current-account");
		userDao.saved = stored;
		UserUpdateResult result = userService.modifyUser("id01", new UserUpdate("new name", null, null, null));
		assertThat(result.credentialsChanged()).isFalse();
		assertThat(result.authEpoch()).isEqualTo("current-account");
		assertThat(userDao.saved.getRole()).isEqualTo("USER");
		assertThat(userDao.saved.getStamps()).isEqualTo(7);
		assertThat(userDao.saved.getPass()).isEqualTo("existing hash");
		assertThat(userDao.saved.getAuthEpoch()).isEqualTo("current-account");
	}

	@Test
	void explicitPasswordChangeRotatesTheEpochButProfileChangesDoNot() {
		User stored = new User("id01", "member", passwordHasher.hash("old pass"), 0, "USER");
		stored.setAuthEpoch("old-epoch");
		userDao.saved = stored;
		UserUpdateResult result = userService.modifyUser("id01", new UserUpdate(null, "new pass", null, null));
		assertThat(result.credentialsChanged()).isTrue();
		assertThat(result.previousAuthEpoch()).isEqualTo("old-epoch");
		assertThat(result.authEpoch()).isEqualTo(userDao.saved.getAuthEpoch());
		assertThat(passwordHasher.matches("new pass", userDao.saved.getPass())).isTrue();
		assertThat(userDao.saved.getAuthEpoch()).isNotEqualTo("old-epoch");
	}

	@Test
	void legacyHashUpgradeDoesNotOverwriteAConcurrentCredentialReset() {
		User original = new User("id01", "member", passwordHasher.legacySha256("old pass"), 0, "USER");
		original.setAuthEpoch("old-account");
		User changed = new User("id01", "member", passwordHasher.hash("new pass"), 0, "USER");
		changed.setAuthEpoch("rotated-account");
		userDao.saved = original;
		userDao.replaceBeforeUpgrade = changed;
		assertThat(userService.login(new User("id01", null, "old pass", 0, null))).isNull();
		assertThat(userDao.saved.getAuthEpoch()).isEqualTo("rotated-account");
		assertThat(passwordHasher.matches("new pass", userDao.saved.getPass())).isTrue();
	}

	@Test
	void anotherHashUpgradeOfTheSameCredentialStillAllowsCurrentLogin() {
		User original = new User("id01", "member", passwordHasher.legacySha256("same pass"), 0, "USER");
		original.setAuthEpoch("same-account");
		User upgraded = new User("id01", "member", passwordHasher.hash("same pass"), 0, "USER");
		upgraded.setAuthEpoch("same-account");
		userDao.saved = original;
		userDao.replaceBeforeUpgrade = upgraded;
		User loggedIn = userService.login(new User("id01", null, "same pass", 0, null));
		assertThat(loggedIn).isNotNull();
		assertThat(loggedIn.getAuthEpoch()).isEqualTo("same-account");
		assertThat(loggedIn.getPass()).isNull();
	}

	@Test
	void removingUserClearsFreeTextBeforeDeletingTheAccount() {
		userDao.saved = new User("id01", "회원", "stored", 0, "USER");
		assertThat(userService.removeUser("id01")).isEqualTo(1);
		assertThat(userDao.clearedOrderNotesFor).isEqualTo("id01");
		assertThat(userDao.notesWereClearedBeforeDelete).isTrue();
		assertThat(userDao.saved).isNull();
	}

	@Test
	void missingUserDoesNotClearUnrelatedOrderNotes() {
		assertThat(userService.removeUser("missing")).isZero();
		assertThat(userDao.clearedOrderNotesFor).isNull();
	}

	private static class FakeUserDao implements UserDao {
		private User saved;
		private String updatedPassword;
		private String clearedOrderNotesFor;
		private boolean notesWereClearedBeforeDelete;
		private User replaceBeforeUpgrade;

		@Override
		public List<User> selectAll() {
			return saved == null ? List.of() : List.of(saved);
		}

		@Override
		public List<User> selectPaged(int limit, int offset) {
			return new ArrayList<>(selectAll());
		}

		@Override
		public int countAll() {
			return selectAll().size();
		}

		@Override
		public int insert(User user) {
			saved = user;
			return 1;
		}

		@Override
		public int update(User user) {
			saved = user;
			return 1;
		}

		@Override
		public int upgradePassword(String id, String expectedHash, String authEpoch, String pass) {
			if (replaceBeforeUpgrade != null) {
				saved = replaceBeforeUpgrade;
			}
			if (!java.util.Objects.equals(saved.getAuthEpoch(), authEpoch) || !saved.getPass().equals(expectedHash)) {
				return 0;
			}
			updatedPassword = pass;
			saved.setPass(pass);
			return 1;
		}

		@Override
		public int delete(String id) {
			notesWereClearedBeforeDelete = id.equals(clearedOrderNotesFor);
			saved = null;
			return 1;
		}

		@Override
		public int clearOrderNotes(String id) {
			clearedOrderNotesFor = id;
			return 1;
		}

		@Override
		public int updateStamp(User user) {
			saved = user;
			return 1;
		}

		@Override
		public int incrementStamps(String id, int quantity) {
			saved.setStamps(saved.getStamps() + quantity);
			return 1;
		}

		@Override
		public User selectById(String userId) {
			return saved != null && saved.getId().equals(userId) ? saved : null;
		}

		@Override
		public User selectByIdForUpdate(String userId) { return selectById(userId); }

		@Override
		public User selectSessionIdentity(String id, String authEpoch) {
			User user = selectById(id);
			return user != null && java.util.Objects.equals(user.getAuthEpoch(), authEpoch) ? user : null;
		}
	}
}
