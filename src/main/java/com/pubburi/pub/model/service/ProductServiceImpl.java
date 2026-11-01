package com.pubburi.pub.model.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

import com.pubburi.pub.controller.api.PageCriteria;
import com.pubburi.pub.controller.api.PageResponse;
import com.pubburi.pub.controller.api.ProductSearchFilters;
import com.pubburi.pub.model.dao.ProductDao;
import com.pubburi.pub.model.dto.Product;

@Service
public class ProductServiceImpl implements ProductService {

	private final ProductDao productDao;
	private final ProductSearchOutbox searchOutbox;

	public ProductServiceImpl(ProductDao productDao, ProductSearchOutbox searchOutbox) {
		this.productDao = productDao;
		this.searchOutbox = searchOutbox;
	}

	@Override
	public List<Product> getProductList() {
		return productDao.selectAll();
	}

	@Override
	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public PageResponse<Product> searchProducts(String type, String q, String sort, ProductSearchFilters filters, PageCriteria criteria) {
		long offset = ((long) criteria.page() - 1) * criteria.size();
		if (offset < 0 || offset > Integer.MAX_VALUE)
			throw new IllegalArgumentException("Product page offset exceeds the supported range");
		String safeType = blankToNull(type);
		String safeQ = blankToNull(q);
		List<Product> products = productDao.selectFiltered(safeType, safeQ, blankToNull(sort), filters, criteria.size(),
				(int) offset);
		return PageResponse.of(products, criteria, productDao.countFiltered(safeType, safeQ, filters));
	}

	@Override
	public Product getProductById(int id) {
		if (id <= 0) {
			return null;
		}
		return productDao.selectById(id);
	}

	@Override
	@Transactional
	public int addProduct(Product product) {
		if (product == null || product.getName() == null || product.getType() == null || product.getPrice() <= 0) {
			return 0;
		}
		int changed = productDao.insert(product);
		if (changed > 0) searchOutbox.recordCurrent(product.getId());
		return changed;
	}

	@Override
	@Transactional
	public int updateProduct(Product product) {
		if (product == null || product.getId() <= 0) {
			return 0;
		}
		if (productDao.selectByIdForUpdate(product.getId()) == null) return 0;
		int changed = productDao.update(product);
		if (changed > 0) searchOutbox.recordCurrent(product.getId());
		return changed;
	}

	@Override
	@Transactional
	public int removeProduct(int id) {
		if (id <= 0) {
			return 0;
		}
		if (productDao.selectByIdForUpdate(id) == null) return 0;
		int changed = productDao.delete(id);
		if (changed > 0) searchOutbox.recordDeleted(id);
		return changed;
	}

	@Override
	@Transactional(readOnly = true)
	public List<Product> getPopularProducts() {
		return productDao.selectPopular();
	}

	private String blankToNull(String value) {
		return value == null || value.trim().isEmpty() ? null : value.trim();
	}
}
