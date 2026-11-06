package com.pubburi.rehearsal.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.util.Objects;

@Entity
@Table(name = "t_user_recommendation_feedback", schema = "pubburi")
@IdClass(JpaRecommendationFeedback.Key.class)
public class JpaRecommendationFeedback {
    public enum Choice { MATCHED, NOT_INTERESTED }

    @Id @Column(name = "user_id", length = 100, nullable = false)
    String userId;
    @Id @Column(name = "product_id", nullable = false)
    Integer productId;
    @Enumerated(EnumType.STRING) @Column(name = "feedback", length = 14, nullable = false)
    Choice feedback;

    protected JpaRecommendationFeedback() { }

    public static class Key implements Serializable {
        public String userId;
        public Integer productId;

        public Key() { }

        @Override public boolean equals(Object value) {
            return value instanceof Key other && Objects.equals(userId, other.userId)
                && Objects.equals(productId, other.productId);
        }

        @Override public int hashCode() {
            return Objects.hash(userId, productId);
        }
    }
}
