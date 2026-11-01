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

	public ProductServiceImpl(ProductDao productDao) {
		this.productDao = productDao;
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
		return productDao.insert(product);
	}

	@Override
	@Transactional
	public int updateProduct(Product product) {
		if (product == null || product.getId() <= 0) {
			return 0;
		}
		return productDao.update(product);
	}

	@Override
	@Transactional
	public int removeProduct(int id) {
		if (id <= 0) {
			return 0;
		}
		return productDao.delete(id);
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
