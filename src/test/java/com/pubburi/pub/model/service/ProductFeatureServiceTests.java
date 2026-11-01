package com.pubburi.pub.model.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.pubburi.pub.model.dao.ProductDao;
import com.pubburi.pub.model.dao.ProductFeatureDao;
import com.pubburi.pub.model.dto.Product;
import com.pubburi.pub.model.dto.ProductFeatures;
import com.pubburi.pub.model.dto.ProductFeatureTag;

class ProductFeatureServiceTests {
	@Test
	void unknownAndReviewedEmptyTagsRemainDistinct() {
		Fixture fixture = new Fixture();
		ProductFeatures unknown = fixture.service().getProductFeatures(7);
		assertThat(unknown.getTasteTags()).isNull();
		assertThat(unknown.getAromaTags()).isNull();
		assertThat(unknown.getVolumeMl()).isNull();

		ProductFeatures changes = features(List.of(), null);
		fixture.service().replaceProductFeatures(7, changes);
		ProductFeatures reviewed = fixture.service().getProductFeatures(7);
		assertThat(reviewed.getTasteTags()).isEmpty();
		assertThat(reviewed.getAromaTags()).isNull();
	}

	@Test
	void replacementLocksTheProductBeforeAtomicallyReplacingHeaderAndTags() {
		Fixture fixture = new Fixture();
		ProductFeatures changes = features(List.of(" Sweet ", "DRY"), List.of(" Citrus "));
		changes.setVolumeMl(750);
		changes.setDescription(" Checked fixture ");
		ProductFeatures result = fixture.service().replaceProductFeatures(7, changes);

		assertThat(fixture.events).containsExactly("lock-product", "upsert-header", "delete-tags", "insert-tags");
		org.mockito.Mockito.verify(fixture.searchOutbox).recordCurrent(7);
		assertThat(result.getProductId()).isEqualTo(7);
		assertThat(result.getTasteTags()).containsExactly("dry", "sweet");
		assertThat(result.getAromaTags()).containsExactly("citrus");
		assertThat(result.getDescription()).isEqualTo("Checked fixture");
		assertThat(result.getSourceName()).isEqualTo("Fixture evidence");
		assertThat(fixture.service().getProductFeatures(7).getTasteTags()).containsExactly("dry", "sweet");
	}

	@Test
	void duplicateNormalizedTagsAreRejectedBeforeAnyDatabaseWrite() {
		Fixture fixture = new Fixture();
		assertThatThrownBy(() -> fixture.service().replaceProductFeatures(7, features(List.of("SWEET", " sweet "), null)))
				.isInstanceOf(IllegalArgumentException.class);
		assertThat(fixture.events).isEmpty();
	}

	@Test
	void reviewedEmptyTagsStillRequireAnEvidenceLabel() {
		Fixture fixture = new Fixture();
		ProductFeatures changes = features(List.of(), null);
		changes.setSourceName(" ");
		assertThatThrownBy(() -> fixture.service().replaceProductFeatures(7, changes))
				.isInstanceOf(IllegalArgumentException.class);
		assertThat(fixture.events).isEmpty();
	}

	@Test
	void emptyPutClearsValuesAndBothKnownFlagsWithoutInventingZeroOrTags() {
		Fixture fixture = new Fixture();
		fixture.service().replaceProductFeatures(7, features(List.of("sweet"), List.of("citrus")));
		ProductFeatures cleared = fixture.service().replaceProductFeatures(7, new ProductFeatures());
		assertThat(cleared.getVolumeMl()).isNull();
		assertThat(cleared.getTasteTags()).isNull();
		assertThat(cleared.getAromaTags()).isNull();
		assertThat(cleared.isTasteKnown()).isFalse();
		assertThat(cleared.isAromaKnown()).isFalse();
		assertThat(fixture.tags).isEmpty();
	}

	@Test
	void missingProductCannotCreateAttributesAndDoesNotWrite() {
		Fixture fixture = new Fixture();
		fixture.exists = false;
		assertThat(fixture.service().getProductFeatures(7)).isNull();
		assertThat(fixture.service().replaceProductFeatures(7, new ProductFeatures())).isNull();
		assertThat(fixture.events).containsExactly("lock-product");
	}

	@Test
	void incompleteTagWriteThrowsInsteadOfReturningSuccessfulReplacement() {
		Fixture fixture = new Fixture();
		fixture.failTagWrite = true;
		assertThatThrownBy(() -> fixture.service().replaceProductFeatures(7, features(List.of("sweet"), null)))
				.isInstanceOf(IllegalStateException.class);
		// The fake observes control flow only; actual rollback is tested against disposable MySQL.
		assertThat(fixture.events).containsExactly("lock-product", "upsert-header", "delete-tags", "insert-tags");
		org.mockito.Mockito.verifyNoInteractions(fixture.searchOutbox);
	}

	private static ProductFeatures features(List<String> taste, List<String> aroma) {
		ProductFeatures features = new ProductFeatures();
		features.setTasteTags(taste);
		features.setAromaTags(aroma);
		features.setSourceName(" Fixture evidence ");
		return features;
	}

	private static class Fixture implements ProductFeatureDao {
		boolean exists = true;
		boolean failTagWrite;
		ProductFeatures stored;
		List<ProductFeatureTag> tags = new ArrayList<>();
		List<String> events = new ArrayList<>();
		ProductSearchOutbox searchOutbox = org.mockito.Mockito.mock(ProductSearchOutbox.class);

		ProductFeatureServiceImpl service() {
			ProductDao productDao = (ProductDao) Proxy.newProxyInstance(ProductDao.class.getClassLoader(),
					new Class<?>[] {ProductDao.class}, (proxy, method, args) -> {
						if (method.getName().equals("selectByIdForUpdate")) {
							events.add("lock-product");
							return exists ? new Product() : null;
						}
						throw new AssertionError("Unexpected product operation: " + method.getName());
					});
			return new ProductFeatureServiceImpl(productDao, this, searchOutbox);
		}

		@Override public ProductFeatures selectByProductId(int id) {
			if (!exists) return null;
			if (stored != null) return stored;
			ProductFeatures unknown = new ProductFeatures();
			unknown.setProductId(id);
			return unknown;
		}
		@Override public List<ProductFeatureTag> selectTags(int id) { return List.copyOf(tags); }
		@Override public int upsert(ProductFeatures features) {
			events.add("upsert-header"); stored = features; return 1;
		}
		@Override public int deleteTags(int id) { events.add("delete-tags"); tags.clear(); return 0; }
		@Override public int insertTags(int id, List<ProductFeatureTag> values) {
			events.add("insert-tags");
			if (failTagWrite) return 0;
			tags.addAll(values); return values.size();
		}
	}
}
