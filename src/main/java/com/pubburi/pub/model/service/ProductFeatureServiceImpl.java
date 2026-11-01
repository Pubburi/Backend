package com.pubburi.pub.model.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pubburi.pub.model.dao.ProductDao;
import com.pubburi.pub.model.dao.ProductFeatureDao;
import com.pubburi.pub.model.dto.ProductFeatures;
import com.pubburi.pub.model.dto.ProductFeatureTag;

@Service
public class ProductFeatureServiceImpl implements ProductFeatureService {
	private final ProductDao productDao;
	private final ProductFeatureDao featureDao;
	private final ProductSearchOutbox searchOutbox;

	public ProductFeatureServiceImpl(ProductDao productDao, ProductFeatureDao featureDao, ProductSearchOutbox searchOutbox) {
		this.productDao = productDao;
		this.featureDao = featureDao;
		this.searchOutbox = searchOutbox;
	}

	@Override
	@Transactional(readOnly = true)
	public ProductFeatures getProductFeatures(int productId) {
		if (productId <= 0) {
			return null;
		}
		ProductFeatures features = featureDao.selectByProductId(productId);
		if (features == null) {
			return null;
		}
		List<ProductFeatureTag> tags = featureDao.selectTags(productId);
		features.setTasteTags(features.isTasteKnown() ? tagsOf(tags, "TASTE") : null);
		features.setAromaTags(features.isAromaKnown() ? tagsOf(tags, "AROMA") : null);
		return features;
	}

	@Override
	@Transactional
	public ProductFeatures replaceProductFeatures(int productId, ProductFeatures changes) {
		if (productId <= 0 || changes == null) {
			return null;
		}
		ProductFeatures normalized = normalize(productId, changes);
		// Serializes replacement with other replacements, product edits and deletion.
		if (productDao.selectByIdForUpdate(productId) == null) {
			return null;
		}
		featureDao.upsert(normalized);
		featureDao.deleteTags(productId);
		List<ProductFeatureTag> tags = new ArrayList<>();
		appendTags(tags, "TASTE", normalized.getTasteTags());
		appendTags(tags, "AROMA", normalized.getAromaTags());
		if (!tags.isEmpty() && featureDao.insertTags(productId, tags) != tags.size()) {
			throw new IllegalStateException("Product feature tags could not be replaced");
		}
		searchOutbox.recordCurrent(productId);
		return normalized;
	}

	private ProductFeatures normalize(int productId, ProductFeatures changes) {
		ProductFeatures normalized = new ProductFeatures();
		normalized.setProductId(productId);
		normalized.setVolumeMl(changes.getVolumeMl());
		normalized.setDescription(blankToNull(changes.getDescription()));
		normalized.setSourceName(blankToNull(changes.getSourceName()));
		normalized.setSourceUrl(blankToNull(changes.getSourceUrl()));
		normalized.setSourceNote(blankToNull(changes.getSourceNote()));
		normalized.setTasteTags(normalizeTags(changes.getTasteTags()));
		normalized.setAromaTags(normalizeTags(changes.getAromaTags()));
		normalized.setTasteKnown(normalized.getTasteTags() != null);
		normalized.setAromaKnown(normalized.getAromaTags() != null);
		if ((normalized.getVolumeMl() != null || normalized.getDescription() != null
				|| normalized.isTasteKnown() || normalized.isAromaKnown()) && normalized.getSourceName() == null) {
			throw new IllegalArgumentException("Known attributes require sourceName");
		}
		return normalized;
	}

	private List<String> normalizeTags(List<String> tags) {
		if (tags == null) {
			return null;
		}
		if (tags.size() > 20) {
			throw new IllegalArgumentException("At most 20 tags per kind are allowed");
		}
		TreeSet<String> normalized = new TreeSet<>();
		for (String value : tags) {
			String tag = blankToNull(value);
			if (tag != null) {
				tag = tag.toLowerCase(Locale.ROOT);
			}
			if (tag == null || tag.length() > 40 || !normalized.add(tag)) {
				throw new IllegalArgumentException("Tags must be nonblank and unique after normalization");
			}
		}
		return List.copyOf(normalized);
	}

	private List<String> tagsOf(List<ProductFeatureTag> tags, String kind) {
		return tags.stream().filter(tag -> kind.equals(tag.kind())).map(ProductFeatureTag::tag).sorted().toList();
	}

	private void appendTags(List<ProductFeatureTag> target, String kind, List<String> values) {
		if (values != null) {
			values.forEach(value -> target.add(new ProductFeatureTag(kind, value)));
		}
	}

	private String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.strip();
	}
}
