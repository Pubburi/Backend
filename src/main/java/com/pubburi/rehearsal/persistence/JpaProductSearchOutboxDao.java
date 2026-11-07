package com.pubburi.rehearsal.persistence;

import com.pubburi.pub.model.dao.ProductSearchOutboxDao;
import com.pubburi.pub.model.dto.ProductFeatures;
import com.pubburi.pub.model.dto.ProductFeatureTag;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Atomic queue boundaries retain explicit SQL semantics and original JSON text. */
@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class JpaProductSearchOutboxDao implements ProductSearchOutboxDao {
    @PersistenceContext private EntityManager entityManager;

    @Override
    public ProductFeatures selectFeaturesForUpdate(int id) {
        List<?> rows = entityManager.createNativeQuery("""
            SELECT product_id, taste_known, aroma_known
            FROM pubburi.t_product_feature WHERE product_id = ?1 FOR UPDATE
            """).setParameter(1, id).getResultList();
        if (rows.isEmpty()) return null;
        Object[] row = (Object[]) rows.get(0);
        var value = new ProductFeatures();
        value.setProductId(((Number) row[0]).intValue());
        value.setTasteKnown((Boolean) row[1]);
        value.setAromaKnown((Boolean) row[2]);
        return value;
    }

    @Override
    public List<ProductFeatureTag> selectTagsForUpdate(int id) {
        List<?> rows = entityManager.createNativeQuery("""
            SELECT kind, tag FROM pubburi.t_product_feature_tag
            WHERE product_id = ?1 FOR UPDATE
            """).setParameter(1, id).getResultList();
        return rows.stream().map(raw -> {
            Object[] row = (Object[]) raw;
            return new ProductFeatureTag((String) row[0], (String) row[1]);
        }).toList();
    }

    @Override
    public void ensureVersion(int id) {
        entityManager.createNativeQuery("""
            INSERT INTO pubburi.t_product_search_version(product_id, source_version)
            VALUES (?1, 0) ON CONFLICT (product_id) DO NOTHING
            """).setParameter(1, id).executeUpdate();
    }

    @Override
    public int advanceVersion(int id) {
        return entityManager.createNativeQuery("""
            UPDATE pubburi.t_product_search_version SET source_version = source_version + 1
            WHERE product_id = ?1 AND source_version < 9223372036854775807
            """).setParameter(1, id).executeUpdate();
    }

    @Override
    public Long selectVersionForUpdate(int id) {
        List<?> rows = entityManager.createNativeQuery("""
            SELECT source_version FROM pubburi.t_product_search_version
            WHERE product_id = ?1 FOR UPDATE
            """).setParameter(1, id).getResultList();
        return rows.isEmpty() ? null : ((Number) rows.get(0)).longValue();
    }

    @Override
    public int append(int id, long version, boolean deleted, String payload) {
        // JSON, not JSONB: PostgreSQL validates syntax without normalizing the bound text.
        return entityManager.createNativeQuery("""
            INSERT INTO pubburi.t_product_search_outbox(product_id, source_version, deleted, payload)
            VALUES (?1, ?2, ?3, CAST(?4 AS json))
            """).setParameter(1, id).setParameter(2, version)
            .setParameter(3, deleted).setParameter(4, payload).executeUpdate();
    }

    @Override
    public int schedule(int id, long version) {
        return entityManager.createNativeQuery("""
            INSERT INTO pubburi.t_product_search_delivery(event_id)
            SELECT event_id FROM pubburi.t_product_search_outbox
            WHERE product_id = ?1 AND source_version = ?2
            """).setParameter(1, id).setParameter(2, version).executeUpdate();
    }
}
