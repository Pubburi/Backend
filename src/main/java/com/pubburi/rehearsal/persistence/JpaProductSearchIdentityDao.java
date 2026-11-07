package com.pubburi.rehearsal.persistence;

import com.pubburi.pub.model.dao.ProductSearchIdentityDao;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional(readOnly = true)
public class JpaProductSearchIdentityDao implements ProductSearchIdentityDao {
    @PersistenceContext private EntityManager entityManager;
    @Override public String selectSourceId() {
        List<?> values = entityManager.createNativeQuery("SELECT source_id::text FROM pubburi.t_product_search_relay_target WHERE singleton_id=1").getResultList();
        return values.isEmpty() ? null : (String) values.get(0);
    }
}
