package com.pubburi.pub.model.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.pubburi.pub.model.dao.UserDao;
import com.pubburi.pub.model.dto.User;

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
	}

	@Test
	void loginMigratesLegacySha256Password() {
		User stored = new User("id01", "홍길동", passwordHasher.legacySha256("pass01"), 5, "USER");
		userDao.saved = stored;

		User result = userService.login(new User("id01", null, "pass01", 0, null));

		assertThat(result).isNotNull();
		assertThat(result.getPass()).isNull();
		assertThat(userDao.updatedPassword).startsWith("$2");
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
		public int updatePassword(String id, String pass) {
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
	}
}
