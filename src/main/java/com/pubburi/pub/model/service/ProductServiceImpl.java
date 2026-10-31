package com.pubburi.pub.model.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pubburi.pub.controller.api.PageCriteria;
import com.pubburi.pub.controller.api.PageResponse;
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
	public PageResponse<Product> searchProducts(String type, String q, String sort, PageCriteria criteria) {
		String safeType = blankToNull(type);
		String safeQ = blankToNull(q);
		List<Product> products = productDao.selectFiltered(safeType, safeQ, blankToNull(sort), criteria.size(),
				criteria.offset());
		return PageResponse.of(products, criteria, productDao.countFiltered(safeType, safeQ));
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
