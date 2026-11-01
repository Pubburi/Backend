package com.pubburi.pub.model.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pubburi.pub.model.dao.ProductDao;
import com.pubburi.pub.model.dao.ProductSearchOutboxDao;
import com.pubburi.pub.model.dto.Product;
import com.pubburi.pub.model.dto.ProductFeatures;
import com.pubburi.pub.model.dto.ProductFeatureTag;

/** No engine IO: the version and immutable projection commit with the business write. */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class ProductSearchOutbox {
    private final ProductDao products;
    private final ProductSearchOutboxDao outbox;
    private final ObjectMapper json;

    public ProductSearchOutbox(ProductDao products, ProductSearchOutboxDao outbox, ObjectMapper json) {
        this.products = products;
        this.outbox = outbox;
        this.json = json;
    }

    public void recordCurrent(int productId) {
        Product product = products.selectByIdForUpdate(productId);
        if (product == null) throw new IllegalStateException("Search projection product is missing");
        ProductFeatures features = outbox.selectFeaturesForUpdate(productId);
        List<ProductFeatureTag> tags = outbox.selectTagsForUpdate(productId);
        Map<String, Object> projection = new LinkedHashMap<>();
        projection.put("name", product.getName());
        projection.put("type", product.getType());
        projection.put("priceKrw", product.getPrice());
        projection.put("abvPercent", product.getAbv());
        projection.put("orderCount", product.getOrderCount());
        projection.put("tasteTags", features != null && features.isTasteKnown() ? tagsOf(tags, "TASTE") : null);
        projection.put("aromaTags", features != null && features.isAromaKnown() ? tagsOf(tags, "AROMA") : null);
        append(productId, false, projection);
    }

    public void recordDeleted(int productId) {
        if (products.selectByIdForUpdate(productId) != null)
            throw new IllegalStateException("Search tombstone product still exists");
        append(productId, true, null);
    }

    private void append(int productId, boolean deleted, Map<String, Object> projection) {
        if (productId <= 0) throw new IllegalArgumentException("Positive product ID required");
        outbox.ensureVersion(productId);
        if (outbox.advanceVersion(productId) != 1)
            throw new IllegalStateException("Search source version cannot advance");
        Long version = outbox.selectVersionForUpdate(productId);
        if (version == null || version <= 0) throw new IllegalStateException("Search source version is invalid");
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("productId", productId);
        event.put("sourceVersion", version);
        event.put("deleted", deleted);
        event.put("document", projection);
        try {
            if (outbox.append(productId, version, deleted, json.writeValueAsString(event)) != 1)
                throw new IllegalStateException("Search outbox event could not be recorded");
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Search projection cannot be serialized", error);
        }
    }

    private List<String> tagsOf(List<ProductFeatureTag> tags, String kind) {
        return tags.stream().filter(tag -> kind.equals(tag.kind())).map(ProductFeatureTag::tag).sorted().toList();
    }
}
