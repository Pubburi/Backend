package com.pubburi.pub.model.dao;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import com.pubburi.pub.model.dto.Product;

public interface ProductDao {

	/**
	 * 모든 상품정보를 조회한다.
	 *
	 * @return 상품 리스트
	 */
	List<Product> selectAll();

	List<Product> selectFiltered(@Param("type") String type, @Param("q") String q, @Param("sort") String sort,
			@Param("limit") int limit, @Param("offset") int offset);

	int countFiltered(@Param("type") String type, @Param("q") String q);

	/**
	 * 주어진 ID에 해당하는 상품을 조회한다.
	 *
	 * @param id 상품 ID
	 * @return 상품 객체
	 */
	Product selectById(int id);

	/**
	 * 인기순으로 상품정보를 조회한다.
	 *
	 * @return 인기상품 리스트
	 */
	List<Product> selectPopular();

	/**
	 * 상품을 등록한다.
	 *
	 * @param product 상품 정보
	 * @return 삽입 결과
	 */
	int insert(Product product);

	/**
	 * 상품 정보를 수정한다.
	 *
	 * @param product 상품 정보
	 * @return 수정 결과
	 */
	int update(Product product);

	/**
	 * 상품을 삭제한다.
	 *
	 * @param id 상품 ID
	 * @return 삭제 결과
	 */
	int delete(int id);

	/**
	 * 상품별 누적 주문 수를 증가시킨다.
	 *
	 * @param productId 상품 ID
	 * @param quantity  증가할 수량
	 * @return 업데이트된 행 수
	 */
	int incrementOrderCount(@Param("productId") int productId, @Param("quantity") int quantity);

	Product selectByExactName(@Param("name") String name);

	List<Product> selectTopNByTypeExcludeName(@Param("type") String type, @Param("excludeName") String excludeName, @Param("n") int n);

}
