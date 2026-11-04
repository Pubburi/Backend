package com.pubburi.rehearsal.persistence;

import com.pubburi.pub.model.dao.CommentDao;
import com.pubburi.pub.model.dto.Comment;
import com.pubburi.pub.controller.api.CommentSort;
import jakarta.persistence.*;
import java.util.List;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional(readOnly = true)
public class JpaCommentDao implements CommentDao {
    private static final String VIEW = "select new com.pubburi.pub.model.dto.Comment(c.id,c.userId,c.productId,c.rating,c.comment) from RehearsalComment c";
    private final CommentRepository comments;
    private final CommentTagRepository tags;
    @PersistenceContext private EntityManager entityManager;
    public JpaCommentDao(CommentRepository comments, CommentTagRepository tags) { this.comments = comments; this.tags = tags; }
    private List<Comment> page(Integer productId, int limit, int offset, CommentSort sort) {
        String order = switch (sort) {
            case LATEST -> "c.id desc";
            case RATING_DESC -> "c.rating desc,c.id desc";
            case RATING_ASC -> "c.rating asc,c.id desc";
        };
        var query = entityManager.createQuery(VIEW + (productId == null ? "" : " where c.productId=:productId") + " order by " + order, Comment.class);
        if (productId != null) query.setParameter("productId", productId);
        return query.setFirstResult(offset).setMaxResults(limit).getResultList();
    }
    @Override public List<Comment> selectByProductId(int productId) { return page(productId, Integer.MAX_VALUE, 0, CommentSort.LATEST); }
    @Override public List<Comment> selectByProductIdPaged(int productId, int limit, int offset, CommentSort sort) { return page(productId, limit, offset, sort); }
    @Override public List<com.pubburi.pub.model.dto.CommentTasteTag> selectTasteTags(List<Integer> ids) { return tags.opinions(ids); }
    @Override @Transactional public int deleteTasteTags(int id) { return tags.deleteForComment(id); }
    @Override @Transactional public int insertTasteTags(int id, List<String> values) {
        tags.saveAllAndFlush(values.stream().map(value -> JpaCommentTag.from(id, value)).toList());
        return values.size();
    }
    @Override public int countByProductId(int productId) {
        return Math.toIntExact(entityManager.createQuery("select count(c) from RehearsalComment c where c.productId=:id", Long.class).setParameter("id", productId).getSingleResult());
    }
    @Override public Double averageRatingByProductId(int productId) {
        Object average = entityManager.createQuery("select avg(c.rating) from RehearsalComment c where c.productId=:id")
                .setParameter("id", productId).getSingleResult();
        if (average == null) return null;
        if (!(average instanceof Number value)) throw new IllegalStateException("Invalid review aggregate type");
        return value.doubleValue();
    }
    @Override public List<Comment> selectAll() { return page(null, Integer.MAX_VALUE, 0, CommentSort.LATEST); }
    @Override public List<Comment> selectAllPaged(int limit, int offset) { return page(null, limit, offset, CommentSort.LATEST); }
    @Override public int countAll() { return Math.toIntExact(comments.count()); }
    @Override public Comment select(int id) {
        var rows = entityManager.createQuery(VIEW + " where c.id=:id", Comment.class).setParameter("id", id).setMaxResults(1).getResultList();
        return rows.isEmpty() ? null : rows.get(0);
    }
    @Override @Transactional public int insert(Comment comment) {
        var row = comments.saveAndFlush(JpaComment.from(comment)); comment.setId(row.id); return 1;
    }
    @Override @Transactional public int update(Comment comment) { return comments.change(comment.getId(), comment.getRating(), comment.getComment()); }
    @Override @Transactional public int delete(int id) { return comments.remove(id); }
}
