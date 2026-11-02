package com.pubburi.rehearsal.persistence;

import com.pubburi.pub.model.dao.ProductDao;
import com.pubburi.pub.model.dao.ProductFeatureDao;
import com.pubburi.pub.model.dto.ProductFeatures;
import com.pubburi.pub.model.service.ProductFeatureServiceImpl;
import com.pubburi.pub.model.service.ProductSearchOutbox;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Reuse shared replacement logic and retain the earlier PG metadata/tag read snapshot. */
@Service
public class JpaProductFeatureService extends ProductFeatureServiceImpl {
    public JpaProductFeatureService(ProductDao products, ProductFeatureDao features, ProductSearchOutbox outbox) {
        super(products, features, outbox);
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ProductFeatures getProductFeatures(int id) {
        return super.getProductFeatures(id);
    }
}
