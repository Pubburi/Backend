package com.pubburi.pub.model.service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import com.pubburi.pub.controller.api.CommentTasteTags;
import com.pubburi.pub.model.dto.CommentTasteTag;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.pubburi.pub.controller.api.PageCriteria;
import com.pubburi.pub.controller.api.PageResponse;
import com.pubburi.pub.controller.api.CommentSort;
import com.pubburi.pub.controller.response.CommentPageResponse;
import com.pubburi.pub.controller.response.CommentRatingSummary;
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
		List<Comment> comments = commentDao.selectByProductIdPaged(productId, criteria.size(), criteria.offset(), CommentSort.LATEST);
		return PageResponse.of(comments, criteria, commentDao.countByProductId(productId));
	}

	@Override
	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public CommentPageResponse<Comment> getCommentsPage(int productId, PageCriteria criteria, CommentSort sort,
			boolean includeSummary) {
        return getCommentsPage(productId, criteria, sort, includeSummary, false);
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public CommentPageResponse<Comment> getCommentsPage(int productId, PageCriteria criteria, CommentSort sort,
            boolean includeSummary, boolean includeTasteTags) {
		if ((long) (criteria.page() - 1) * criteria.size() > Integer.MAX_VALUE) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Review page offset is too large");
		}
		if (productId <= 0) {
			return CommentPageResponse.from(PageResponse.of(List.of(), criteria, 0),
					includeSummary ? CommentRatingSummary.from(0, null) : null);
		}
		var comments = commentDao.selectByProductIdPaged(productId, criteria.size(), criteria.offset(), sort);
        if (includeTasteTags && !comments.isEmpty()) {
            Map<Integer, List<String>> tags = commentDao.selectTasteTags(comments.stream().map(Comment::getId).toList())
                    .stream().collect(Collectors.groupingBy(CommentTasteTag::commentId,
                            Collectors.mapping(CommentTasteTag::tag, Collectors.toList())));
            comments.forEach(comment -> comment.setTasteTags(tags.getOrDefault(comment.getId(), List.of())
                    .stream().sorted().toList()));
        }
		int count = commentDao.countByProductId(productId);
		var summary = includeSummary ? CommentRatingSummary.from(count, commentDao.averageRatingByProductId(productId)) : null;
		return CommentPageResponse.from(PageResponse.of(comments, criteria, count), summary);
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
        var tags = CommentTasteTags.normalize(comment.getTasteTags());
        int changed = commentDao.insert(comment);
        if (changed > 0 && tags != null && !tags.isEmpty()) commentDao.insertTasteTags(comment.getId(), tags);
        return changed;
	}

	@Override
	@Transactional
	public int updateComment(Comment comment) {
		if (comment == null || comment.getId() <= 0) {
			return 0;
		}
        var tags = CommentTasteTags.normalize(comment.getTasteTags());
        // The row UPDATE serializes competing opinion replacements in the same transaction.
        int changed = commentDao.update(comment);
        if (changed > 0 && tags != null) {
            commentDao.deleteTasteTags(comment.getId());
            if (!tags.isEmpty()) commentDao.insertTasteTags(comment.getId(), tags);
        }
        return changed;
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
