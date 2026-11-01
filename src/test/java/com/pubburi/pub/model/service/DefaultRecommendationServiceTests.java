package com.pubburi.pub.model.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.pubburi.pub.controller.response.DefaultRecommendationResponse.Source;
import com.pubburi.pub.model.dao.ProductDao;
import com.pubburi.pub.model.dto.Product;
import com.pubburi.pub.recommendation.BaselineGateway;

class DefaultRecommendationServiceTests {
	@Test
	void validSelectionReloadsLatestPriceAndKeepsRemoteOrderWithoutATransaction() {
		Fixture f = new Fixture();
		BaselineGateway gateway = (ids, size) -> {
			assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
			assertThat(ids).containsExactly(2, 5, 9);
			f.price = 7777; return Optional.of(List.of(2, 5));
		};
		var response = f.service(gateway).getDefaults(2);
		assertThat(response.source()).isEqualTo(Source.BASELINE);
		assertThat(response.items()).extracting(p -> p.id()).containsExactly(2, 5);
		assertThat(response.items()).extracting(p -> p.price()).containsOnly(7777);
		assertThat(f.events).containsExactly("candidates:1000", "current:[2, 5]");
	}

	@Test
	void unavailableOrUntrustedSelectionUsesCurrentDbOrder() {
		for (Optional<List<Integer>> selection : List.<Optional<List<Integer>>>of(Optional.empty(), Optional.of(List.of(9, 2)),
				Optional.of(List.of(2, 2)), Optional.of(List.of(99)), Optional.of(List.of()))) {
			Fixture f = new Fixture();
			var response = f.service((ids, size) -> selection).getDefaults(2);
			assertThat(response.source()).isEqualTo(Source.FALLBACK);
			assertThat(response.items()).extracting(p -> p.id()).containsExactly(2, 5);
			assertThat(f.events).containsExactly("candidates:1000", "candidates:2");
		}
	}

	@Test
	void deletionAfterRemoteCallUsesARefreshedFallbackNotAPartialSnapshot() {
		Fixture f = new Fixture();
		var response = f.service((ids, size) -> { f.deleted = true; return Optional.of(List.of(2, 5)); }).getDefaults(2);
		assertThat(response.source()).isEqualTo(Source.FALLBACK);
		assertThat(response.items()).extracting(p -> p.id()).containsExactly(5, 9);
		assertThat(f.events).containsExactly("candidates:1000", "current:[2, 5]", "candidates:2");
	}

	@Test
	void emptyCatalogDoesNotCallUpstreamButDbFailuresRemainFailures() {
		Fixture f = new Fixture(); f.empty = true;
		var response = f.service((ids, size) -> { throw new AssertionError("must not call"); }).getDefaults(4);
		assertThat(response.items()).isEmpty(); assertThat(response.source()).isEqualTo(Source.FALLBACK);
		f.empty = false; f.failure = true;
		assertThatThrownBy(() -> f.service((ids, size) -> Optional.empty()).getDefaults(4)).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void latestDbFailureCannotBeHiddenAsAnEmptySuccessAndSizeIsBounded() {
		Fixture f = new Fixture();
		assertThatThrownBy(() -> f.service((ids, size) -> { f.failure = true; return Optional.of(List.of(2)); }).getDefaults(1))
				.isInstanceOf(IllegalStateException.class);
		for (int size : List.of(0, 13))
			assertThatThrownBy(() -> f.service((ids, limit) -> Optional.empty()).getDefaults(size)).isInstanceOf(IllegalArgumentException.class);
	}

	private static class Fixture {
		List<String> events = new ArrayList<>(); int price = 1000; boolean deleted; boolean empty; boolean failure;
		List<Product> rows() {
			return empty ? List.of() : (deleted ? List.of(5, 9) : List.of(2, 5, 9)).stream()
					.map(id -> new Product(id, "Owned fixture", "맥주", price, "fixture.png", 5)).toList();
		}
		DefaultRecommendationService service(BaselineGateway gateway) {
			ProductDao dao = (ProductDao) Proxy.newProxyInstance(ProductDao.class.getClassLoader(), new Class<?>[] {ProductDao.class},
					(proxy, method, args) -> {
						if (failure) throw new IllegalStateException("Controlled DB failure");
						if (method.getName().equals("selectDefaultCandidates")) {
							events.add("candidates:" + args[0]); return rows().stream().limit((int) args[0]).toList();
						}
						if (method.getName().equals("selectCurrentByIds")) {
							events.add("current:" + args[0]); return rows().stream().filter(p -> ((List<?>) args[0]).contains(p.getId())).sorted((a,b) -> b.getId()-a.getId()).toList();
						}
						throw new AssertionError(method.getName());
					});
			return new DefaultRecommendationService(dao, gateway);
		}
	}
}
