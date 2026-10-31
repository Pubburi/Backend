package com.pubburi.pub.model.service;

import java.util.List;

import com.pubburi.pub.controller.api.PageCriteria;
import com.pubburi.pub.controller.api.PageResponse;
import com.pubburi.pub.model.dto.Product;

public interface ProductService {

	/**
	 * 모든 상품정보를 조회한다.
	 *
	 * @return 상품 리스트
	 */
	List<Product> getProductList();

	PageResponse<Product> searchProducts(String type, String q, String sort, PageCriteria criteria);

	/**
	 * 주어진 ID에 해당하는 상품을 조회한다.
	 *
	 * @param id 상품 ID
	 * @return 상품 객체
	 */
	Product getProductById(int id);

	/**
	 * 상품을 등록한다.
	 *
	 * @param product 상품 정보
	 * @return 처리 결과
	 */
	int addProduct(Product product);

	/**
	 * 상품 정보를 수정한다.
	 *
	 * @param product 상품 정보
	 * @return 처리 결과
	 */
	int updateProduct(Product product);

	/**
	 * 상품을 삭제한다.
	 *
	 * @param id 상품 ID
	 * @return 처리 결과
	 */
	int removeProduct(int id);

	// ▼ 인기순 조회 추가
	List<Product> getPopularProducts();
}
