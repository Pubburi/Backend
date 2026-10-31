package com.pubburi.pub.model.service;

import java.util.List;

import com.pubburi.pub.controller.api.PageCriteria;
import com.pubburi.pub.controller.api.PageResponse;
import com.pubburi.pub.model.dto.Comment;

public interface CommentService {

	/**
	 * 특정 상품에 대한 모든 리뷰를 조회한다.
	 *
	 * @param productId 상품 ID
	 * @return 리뷰 리스트
	 */
	List<Comment> getCommentsByProductId(int productId);

	PageResponse<Comment> getCommentsByProductId(int productId, PageCriteria criteria);

	List<Comment> getAllComments();

	PageResponse<Comment> getAllComments(PageCriteria criteria);

	/**
	 * 리뷰를 등록한다.
	 *
	 * @param comment 리뷰 정보
	 * @return 처리 결과
	 */
	int addComment(Comment comment);

	/**
	 * 리뷰를 수정한다.
	 *
	 * @param comment 리뷰 정보
	 * @return 처리 결과
	 */
	int updateComment(Comment comment);

	/**
	 * 리뷰를 삭제한다.
	 *
	 * @param id 리뷰 ID
	 * @return 처리 결과
	 */
	int removeComment(int id);

	/**
	 * 리뷰 ID로 단일 리뷰를 조회한다.
	 *
	 * @param commentId 리뷰 ID
	 * @return 리뷰 객체
	 */
	Comment getCommentById(int commentId);
}
