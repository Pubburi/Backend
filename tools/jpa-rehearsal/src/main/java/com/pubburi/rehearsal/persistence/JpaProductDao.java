package com.pubburi.rehearsal.persistence;

import com.pubburi.pub.controller.api.ProductSearchFilters;
import com.pubburi.pub.model.dao.ProductDao;
import com.pubburi.pub.model.dto.Product;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional(readOnly = true)
public class JpaProductDao implements ProductDao {
    private final ProductRepository products;
    private final JpaProductQueries reads;
    @PersistenceContext private EntityManager entityManager;

    public JpaProductDao(ProductRepository products, JpaProductQueries reads) {
        this.products = products;
        this.reads = reads;
    }

    @Override public List<Product> selectAll() { return reads.byIdOrder(Integer.MAX_VALUE); }
    @Override public List<Product> selectDefaultCandidates(int limit) { return reads.byIdOrder(limit); }
    @Override public List<Product> selectCurrentByIds(List<Integer> ids) { return reads.currentByIds(ids); }
    @Override public Product selectById(int id) { return reads.find(id); }
    @Override public List<Product> selectPopular() { return reads.popular(); }
    @Override public Product selectByExactName(String name) { return reads.exactName(name); }
    @Override public List<Product> selectTopNByTypeExcludeName(String type, String name, int n) {
        return reads.topByTypeExcludingName(type, name, n);
    }
    @Override public List<Product> selectFiltered(String type, String q, String sort,
            ProductSearchFilters filters, int limit, int offset) {
        return reads.filtered(type, q, sort, filters, limit, offset);
    }
    @Override public int countFiltered(String type, String q, ProductSearchFilters filters) {
        return reads.count(type, q, filters);
    }

    @Override
    @Transactional
    public Product selectByIdForUpdate(int id) {
        // Full FOR UPDATE also conflicts with FK KEY SHARE; do not downgrade to NO KEY UPDATE.
        List<?> rows = entityManager.createNativeQuery("""
            SELECT id, name, type, price, img, abv, order_count
            FROM pubburi.t_product WHERE id = ?1 FOR UPDATE
            """).setParameter(1, id).getResultList();
        if (rows.isEmpty()) return null;
        Object[] row = (Object[]) rows.get(0);
        return new Product(((Number) row[0]).intValue(), (String) row[1], (String) row[2],
            ((Number) row[3]).intValue(), (String) row[4], ((Number) row[5]).floatValue(),
            ((Number) row[6]).intValue());
    }

    @Override
    @Transactional
    public int insert(Product product) {
        var row = products.saveAndFlush(JpaProduct.from(product));
        product.setId(row.id);
        return 1;
    }

    @Override
    @Transactional
    public int update(Product product) {
        var row = products.findById(product.getId());
        if (row.isEmpty()) return 0;
        row.get().apply(product);
        products.flush();
        return 1;
    }

    @Override
    @Transactional
    public int delete(int id) {
        var row = products.findById(id);
        if (row.isEmpty()) return 0;
        products.delete(row.get());
        products.flush();
        // FK cascades and SET NULL run in PostgreSQL; discard any previously managed dependants.
        entityManager.clear();
        return 1;
    }

    @Override
    @Transactional
    public int incrementOrderCount(int productId, int quantity) {
        entityManager.flush();
        int changed = entityManager.createNativeQuery("""
            UPDATE pubburi.t_product SET order_count = order_count + ?2 WHERE id = ?1
            """).setParameter(1, productId).setParameter(2, quantity).executeUpdate();
        entityManager.clear();
        return changed;
    }
}
