package com.pubburi.pub.model.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.pubburi.pub.controller.api.PageCriteria;
import com.pubburi.pub.model.dao.FavoriteDao;
import com.pubburi.pub.model.dao.ProductDao;
import com.pubburi.pub.model.dao.UserDao;
import com.pubburi.pub.model.dto.Product;
import com.pubburi.pub.model.dto.User;

class FavoriteServiceTests {
	@Test
	void oneStateReadDistinguishesAbsenceFromExpiredIdentityWithoutListsOrWrites() {
		Fixture fixture = new Fixture(); fixture.ids.add(7);
		assertThat(fixture.service().getOwnedState("member", "current", 7)).isTrue();
		assertThat(fixture.service().getOwnedState("member", "current", 8)).isFalse();
		fixture.ids.clear(); fixture.productExists = false;
		assertThat(fixture.service().getOwnedState("member", "current", 7)).isFalse();
		assertThat(fixture.service().getOwnedState("member", "old", 7)).isNull();
		fixture.userExists = false;
		assertThat(fixture.service().getOwnedState("member", "current", 7)).isNull();
		assertThat(fixture.events).containsExactly("state:member:current:7", "state:member:current:8",
				"state:member:current:7", "state:member:old:7", "state:member:current:7");
		assertThat(fixture.ids).isEmpty();
	}

	@Test
	void invalidStateIdentityAndReadFailureCannotBeDisguisedAsAnUnsavedProduct() {
		Fixture fixture = new Fixture();
		assertThat(fixture.service().getOwnedState("member", "", 7)).isNull();
		assertThatThrownBy(() -> fixture.service().getOwnedState("member", "current", 0))
				.isInstanceOf(IllegalArgumentException.class);
		assertThat(fixture.events).isEmpty();
		fixture.failRead = true;
		assertThatThrownBy(() -> fixture.service().getOwnedState("member", "current", 7))
				.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void activeEmptyPageDiffersFromExpiredAccountAndBothQueriesBindTheEpoch() {
		Fixture fixture = new Fixture();
		var page = fixture.service().getOwnedPage("member", "current", PageCriteria.of(1, 20, 20));
		assertThat(page.items()).isEmpty(); assertThat(page.total()).isZero();
		assertThat(fixture.events).containsExactly("identity:member:current", "count:member:current", "page:member:current:20:0");
		fixture.events.clear();
		assertThat(fixture.service().getOwnedPage("member", "old", PageCriteria.of(1, 20, 20))).isNull();
		assertThat(fixture.events).containsExactly("identity:member:old");
	}

	@Test
	void repeatedPutPreservesOneMembershipAndUsesTheUserThenProductLockOrder() {
		Fixture fixture = new Fixture();
		assertThat(fixture.service().setOwned("member", "current", 7, true)).isEqualTo(FavoriteService.MutationResult.PRESENT);
		assertThat(fixture.service().setOwned("member", "current", 7, true)).isEqualTo(FavoriteService.MutationResult.PRESENT);
		assertThat(fixture.ids).containsExactly(7);
		assertThat(fixture.events).containsExactly("lock-user:member", "lock-product:7", "ensure:member:7",
				"lock-user:member", "lock-product:7", "ensure:member:7");
	}

	@Test
	void repeatedDeleteAndMissingProductDeleteDoNotNeedAProductLock() {
		Fixture fixture = new Fixture(); fixture.ids.add(7); fixture.productExists = false;
		assertThat(fixture.service().setOwned("member", "current", 7, false)).isEqualTo(FavoriteService.MutationResult.ABSENT);
		assertThat(fixture.service().setOwned("member", "current", 7, false)).isEqualTo(FavoriteService.MutationResult.ABSENT);
		assertThat(fixture.ids).isEmpty();
		assertThat(fixture.events).containsExactly("lock-user:member", "delete:member:7", "lock-user:member", "delete:member:7");
	}

	@Test
	void anOldOrDeletedAccountCannotWriteToTheCurrentIncarnation() {
		Fixture fixture = new Fixture();
		assertThat(fixture.service().setOwned("member", "old", 7, true)).isEqualTo(FavoriteService.MutationResult.ACCOUNT_EXPIRED);
		fixture.userExists = false;
		assertThat(fixture.service().setOwned("member", "current", 7, false)).isEqualTo(FavoriteService.MutationResult.ACCOUNT_EXPIRED);
		assertThat(fixture.events).containsExactly("lock-user:member", "lock-user:member");
		assertThat(fixture.ids).isEmpty();
	}

	@Test
	void missingCatalogTargetAndWriteFailureCannotReturnSuccessfulMembership() {
		Fixture fixture = new Fixture(); fixture.productExists = false;
		assertThat(fixture.service().setOwned("member", "current", 7, true)).isEqualTo(FavoriteService.MutationResult.PRODUCT_MISSING);
		assertThat(fixture.ids).isEmpty();
		fixture.productExists = true; fixture.failInsert = true;
		assertThatThrownBy(() -> fixture.service().setOwned("member", "current", 7, true)).isInstanceOf(IllegalStateException.class);
		assertThat(fixture.ids).isEmpty();
	}

	@Test
	void paginationOverflowIsRejectedBeforeItCanProduceANegativeSqlOffset() {
		Fixture fixture = new Fixture();
		assertThatThrownBy(() -> fixture.service().getOwnedPage("member", "current", PageCriteria.of(Integer.MAX_VALUE, 100, 20)))
				.isInstanceOf(IllegalArgumentException.class);
		assertThat(fixture.events).containsExactly("identity:member:current");
	}

	private static class Fixture implements FavoriteDao {
		boolean userExists = true; boolean productExists = true; boolean failInsert; boolean failRead;
		Set<Integer> ids = new LinkedHashSet<>(); List<String> events = new ArrayList<>();
		FavoriteService service() {
			UserDao userDao = (UserDao) Proxy.newProxyInstance(UserDao.class.getClassLoader(), new Class<?>[] {UserDao.class},
					(proxy, method, args) -> {
						if ("selectByIdForUpdate".equals(method.getName())) events.add("lock-user:" + args[0]);
						else if ("selectSessionIdentity".equals(method.getName())) {
							events.add("identity:" + args[0] + ":" + args[1]);
							if (!"current".equals(args[1])) return null;
						} else throw new AssertionError(method.getName());
						if (!userExists) return null;
						User user = new User(); user.setId((String) args[0]); user.setAuthEpoch("current"); return user;
					});
			ProductDao products = (ProductDao) Proxy.newProxyInstance(ProductDao.class.getClassLoader(), new Class<?>[] {ProductDao.class},
					(proxy, method, args) -> {
						if (!"selectByIdForUpdate".equals(method.getName())) throw new AssertionError(method.getName());
						events.add("lock-product:" + args[0]); return productExists ? new Product() : null;
					});
			return new FavoriteServiceImpl(this, userDao, products);
		}
		@Override public Boolean selectOwnedState(String id, String epoch, int productId) {
			events.add("state:" + id + ":" + epoch + ":" + productId);
			if (failRead) throw new IllegalStateException("Controlled read failure");
			return userExists && "current".equals(epoch) ? ids.contains(productId) : null;
		}
		@Override public List<Product> selectOwnedPage(String id, String epoch, int limit, int offset) {
			events.add("page:" + id + ":" + epoch + ":" + limit + ":" + offset); return List.of();
		}
		@Override public int countOwned(String id, String epoch) { events.add("count:" + id + ":" + epoch); return ids.size(); }
		@Override public int ensurePresent(String id, int productId) {
			events.add("ensure:" + id + ":" + productId);
			if (failInsert) throw new IllegalStateException("Controlled write failure");
			return ids.add(productId) ? 1 : 0;
		}
		@Override public int deleteOwned(String id, int productId) {
			events.add("delete:" + id + ":" + productId); return ids.remove(productId) ? 1 : 0;
		}
	}
}
