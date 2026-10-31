package com.pubburi.pub.model.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pubburi.pub.controller.api.PageCriteria;
import com.pubburi.pub.controller.api.PageResponse;
import com.pubburi.pub.model.dao.CommentDao;
import com.pubburi.pub.model.dto.Comment;

@Service
public class CommentServiceImpl implements CommentService {

	private final CommentDao commentDao;

	public CommentServiceImpl(CommentDao commentDao) {
		this.commentDao = commentDao;
	}

	@Override
	public List<Comment> getCommentsByProductId(int productId) {
		if (productId <= 0) {
			return List.of();
		}
		return commentDao.selectByProductId(productId);
	}

	@Override
	public PageResponse<Comment> getCommentsByProductId(int productId, PageCriteria criteria) {
		if (productId <= 0) {
			return PageResponse.of(List.of(), criteria, 0);
		}
		List<Comment> comments = commentDao.selectByProductIdPaged(productId, criteria.size(), criteria.offset());
		return PageResponse.of(comments, criteria, commentDao.countByProductId(productId));
	}

	@Override
	public List<Comment> getAllComments() {
		return commentDao.selectAll();
	}

	@Override
	public PageResponse<Comment> getAllComments(PageCriteria criteria) {
		List<Comment> comments = commentDao.selectAllPaged(criteria.size(), criteria.offset());
		return PageResponse.of(comments, criteria, commentDao.countAll());
	}

	@Override
	@Transactional
	public int addComment(Comment comment) {
		if (comment == null || comment.getUserId() == null || comment.getProductId() <= 0 || comment.getRating() < 0) {
			return 0;
		}
		return commentDao.insert(comment);
	}

	@Override
	@Transactional
	public int updateComment(Comment comment) {
		if (comment == null || comment.getId() <= 0) {
			return 0;
		}
		return commentDao.update(comment);
	}

	@Override
	@Transactional
	public int removeComment(int id) {
		if (id <= 0) {
			return 0;
		}
		return commentDao.delete(id);
	}

	@Override
	public Comment getCommentById(int commentId) {
		if (commentId <= 0) {
			return null;
		}
		return commentDao.select(commentId);
	}
}
