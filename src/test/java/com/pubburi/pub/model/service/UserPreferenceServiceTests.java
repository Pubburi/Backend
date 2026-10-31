package com.pubburi.pub.model.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.pubburi.pub.model.dao.UserDao;
import com.pubburi.pub.model.dao.UserPreferenceDao;
import com.pubburi.pub.model.dto.User;
import com.pubburi.pub.model.dto.UserPreferences;
import com.pubburi.pub.model.dto.UserPreferenceValue;

class UserPreferenceServiceTests {
	@Test
	void activeUnknownSurveyIsDifferentFromAnExpiredIncarnation() {
		Fixture fixture = new Fixture();
		UserPreferences unknown = fixture.service().getOwned("member", "current");
		assertThat(unknown.getSurveyStatus()).isEqualTo("NOT_STARTED");
		assertThat(unknown.getTasteTags()).isNull();
		assertThat(unknown.getMinPriceKrw()).isNull();
		fixture.events.clear();
		assertThat(fixture.service().getOwned("member", "old")).isNull();
		assertThat(fixture.events).containsExactly("read:member:old");
	}

	@Test
	void writeCannotBindAnOldGuardResultToARecreatedOrResetAccount() {
		Fixture fixture = new Fixture();
		assertThat(fixture.service().replaceOwned("member", "old", completed())).isNull();
		assertThat(fixture.events).containsExactly("lock:member");
		assertThat(fixture.stored).isNull();
		fixture.exists = false;
		assertThat(fixture.service().replaceOwned("member", "current", completed())).isNull();
		assertThat(fixture.stored).isNull();
	}

	@Test
	void explicitSoftTargetsNeverWidenHardBoundsAndZeroRemainsKnown() {
		Fixture fixture = new Fixture();
		UserPreferences input = completed();
		input.setCategories(List.of(" 탁주 "));
		input.setTasteTags(List.of("SWEET", " dry "));
		input.setAromaTags(List.of());
		input.setTargetAbvPercent(new BigDecimal("80"));
		input.setMinAbvPercent(BigDecimal.ZERO);
		input.setMaxAbvPercent(new BigDecimal("10.25"));
		input.setMinPriceKrw(0);
		input.setMaxPriceKrw(0);
		UserPreferences saved = fixture.service().replaceOwned("member", "current", input);
		assertThat(saved.getCategories()).containsExactly("탁주");
		assertThat(saved.getTasteTags()).containsExactly("dry", "sweet");
		assertThat(saved.getAromaTags()).isEmpty();
		assertThat(saved.getTargetAbvPercent()).isEqualByComparingTo("80");
		assertThat(saved.getMaxAbvPercent()).isEqualByComparingTo("10.25");
		assertThat(saved.getMaxPriceKrw()).isZero();
		assertThat(fixture.events).containsExactly("lock:member", "header:member", "delete-values:member", "insert-values:member");
		assertThat(fixture.service().getOwned("member", "current").getAromaTags()).isEmpty();
	}

	@Test
	void skipAndResetClearOldListsButCompletedAllUnknownIsAllowed() {
		Fixture fixture = new Fixture();
		UserPreferences input = completed(); input.setTasteTags(List.of("sweet"));
		fixture.service().replaceOwned("member", "current", input);
		for (String status : List.of("SKIPPED", "NOT_STARTED", "COMPLETED")) {
			UserPreferences replacement = new UserPreferences(); replacement.setSurveyStatus(status);
			UserPreferences saved = fixture.service().replaceOwned("member", "current", replacement);
			assertThat(saved.getSurveyStatus()).isEqualTo(status);
			assertThat(saved.getTasteTags()).isNull();
			assertThat(fixture.values).isEmpty();
		}
	}

	@Test
	void skippedEmptyArrayStillCountsAsAnExplicitValue() {
		Fixture fixture = new Fixture();
		UserPreferences input = new UserPreferences(); input.setSurveyStatus("SKIPPED"); input.setTasteTags(List.of());
		assertThatThrownBy(() -> fixture.service().replaceOwned("member", "current", input)).isInstanceOf(IllegalArgumentException.class);
		assertThat(fixture.stored).isNull();
	}

	@Test
	void duplicatesUnknownCategoriesAndReversedRangesDoNotWrite() {
		for (int scenario = 0; scenario < 4; scenario++) {
			Fixture fixture = new Fixture(); UserPreferences input = completed();
			if (scenario == 0) input.setTasteTags(List.of("SWEET", " sweet "));
			if (scenario == 1) input.setCategories(List.of("invented-category"));
			if (scenario == 2) { input.setMinPriceKrw(200); input.setMaxPriceKrw(100); }
			if (scenario == 3) input.setTargetAbvPercent(new BigDecimal("10.123"));
			assertThatThrownBy(() -> fixture.service().replaceOwned("member", "current", input)).isInstanceOf(IllegalArgumentException.class);
			assertThat(fixture.stored).isNull();
		}
	}

	@Test
	void failedValueInsertThrowsWithinTheReplacementTransaction() {
		Fixture fixture = new Fixture(); fixture.failInsert = true;
		UserPreferences input = completed(); input.setTasteTags(List.of("sweet"));
		assertThatThrownBy(() -> fixture.service().replaceOwned("member", "current", input)).isInstanceOf(IllegalStateException.class);
		// Actual rollback is checked with the disposable DB; this fake checks the failure boundary only.
		assertThat(fixture.events).containsExactly("lock:member", "header:member", "delete-values:member", "insert-values:member");
	}

	private static UserPreferences completed() {
		UserPreferences value = new UserPreferences(); value.setSurveyStatus("COMPLETED"); return value;
	}

	private static class Fixture implements UserPreferenceDao {
		boolean exists = true; boolean failInsert;
		UserPreferences stored;
		List<UserPreferenceValue> values = new ArrayList<>();
		List<String> events = new ArrayList<>();
		UserPreferenceService service() {
			UserDao userDao = (UserDao) Proxy.newProxyInstance(UserDao.class.getClassLoader(), new Class<?>[] {UserDao.class},
					(proxy, method, args) -> {
						if (!"selectByIdForUpdate".equals(method.getName())) throw new AssertionError(method.getName());
						events.add("lock:" + args[0]);
						if (!exists) return null;
						User user = new User(); user.setId((String) args[0]); user.setAuthEpoch("current"); return user;
					});
			return new UserPreferenceServiceImpl(userDao, this);
		}
		@Override public UserPreferences selectOwned(String id, String epoch) {
			events.add("read:" + id + ":" + epoch);
			if (!exists || !"current".equals(epoch)) return null;
			if (stored != null) return stored;
			UserPreferences empty = new UserPreferences(); empty.setUserId(id); empty.setSurveyStatus("NOT_STARTED"); return empty;
		}
		@Override public List<UserPreferenceValue> selectOwnedValues(String id, String epoch) {
			events.add("read-values:" + id + ":" + epoch); return List.copyOf(values);
		}
		@Override public int upsert(UserPreferences value) { events.add("header:" + value.getUserId()); stored = value; return 1; }
		@Override public int deleteValues(String id) { events.add("delete-values:" + id); values.clear(); return 0; }
		@Override public int insertValues(String id, List<UserPreferenceValue> entries) {
			events.add("insert-values:" + id); if (failInsert) return 0; values.addAll(entries); return entries.size();
		}
	}
}
