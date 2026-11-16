package com.pubburi.rehearsal.persistence;

import com.pubburi.pub.model.dao.ProductFeatureDao;
import com.pubburi.pub.model.dto.ProductFeatures;
import com.pubburi.pub.model.dto.ProductFeatureTag;
import java.util.List;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional(readOnly = true)
public class JpaProductFeatureDao implements ProductFeatureDao {
    private final JpaProductQueries reads;
    private final ProductFeatureRepository features;
    private final ProductTagRepository tags;

    public JpaProductFeatureDao(JpaProductQueries reads, ProductFeatureRepository features,
            ProductTagRepository tags) {
        this.reads = reads;
        this.features = features;
        this.tags = tags;
    }

    @Override public ProductFeatures selectByProductId(int id) { return reads.featureHeader(id); }
    @Override public List<ProductFeatureTag> selectTags(int id) { return reads.tags(id); }

    @Override
    @Transactional
    public int upsert(ProductFeatures value) {
        var row = features.findById(value.getProductId())
            .orElseGet(() -> JpaProductFeature.from(value));
        row.apply(value);
        features.saveAndFlush(row);
        return 1;
    }

    @Override
    @Transactional
    public int deleteTags(int id) {
        return tags.deleteForProduct(id);
    }

    @Override
    @Transactional
    public int insertTags(int id, List<ProductFeatureTag> values) {
        if (values.isEmpty()) return 0;
        tags.saveAllAndFlush(values.stream().map(value -> JpaProductTag.from(id, value)).toList());
        return values.size();
    }
}
