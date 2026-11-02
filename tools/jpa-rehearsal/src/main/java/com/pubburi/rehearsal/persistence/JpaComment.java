package com.pubburi.rehearsal.persistence;

import com.pubburi.pub.model.dto.Comment;
import jakarta.persistence.*;

@Entity(name = "RehearsalComment")
@Table(name = "t_comment", schema = "pubburi")
public class JpaComment {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) Integer id;
    @Column(name = "user_id", length = 100, nullable = false) String userId;
    @Column(name = "product_id", nullable = false) int productId;
    @Column(nullable = false) float rating;
    @Column(length = 200) String comment;
    protected JpaComment() { }
    static JpaComment from(Comment value) {
        var row = new JpaComment(); row.userId = value.getUserId(); row.productId = value.getProductId();
        row.rating = value.getRating(); row.comment = value.getComment(); return row;
    }
}
