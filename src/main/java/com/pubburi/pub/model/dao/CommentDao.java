package com.pubburi.pub.model.dao;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import com.pubburi.pub.model.dto.Comment;

public interface CommentDao {
	/**
	 * 특정 상품에 대한 모든 리뷰를 조회한다.
	 *
	 * @param productId 상품 ID
	 * @return 리뷰 리스트
	 */
	List<Comment> selectByProductId(int productId);

	List<Comment> selectByProductIdPaged(@Param("productId") int productId, @Param("limit") int limit,
			@Param("offset") int offset);

	int countByProductId(int productId);

	List<Comment> selectAll();

	List<Comment> selectAllPaged(@Param("limit") int limit, @Param("offset") int offset);

	int countAll();

	/**
	 * 리뷰를 등록한다.
	 *
	 * @param comment 리뷰 정보
	 * @return 삽입 결과
	 */
	int insert(Comment comment);

	/**
	 * 리뷰를 수정한다.
	 *
	 * @param comment 리뷰 정보
	 * @return 삽입 결과
	 */
	int update(Comment comment);

	/**
	 * 리뷰를 삭제한다.
	 *
	 * @param id 리뷰 ID
	 * @return 삭제 결과
	 */
	int delete(int id);

	/**
	 * comment 단건 조회.
	 *
	 * @param commentId
	 * @return
	 */
	Comment select(int commentId);
}
