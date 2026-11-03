package com.pubburi.rehearsal.persistence;

import jakarta.persistence.*;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

@Entity(name = "RehearsalFavorite")
@Table(name = "t_user_favorite", schema = "pubburi")
@IdClass(JpaFavorite.Key.class)
public class JpaFavorite {
    @Id @Column(name = "user_id", length = 100, nullable = false) String userId;
    @Id @Column(name = "product_id", nullable = false) Integer productId;
    @Column(name = "created_at", nullable = false, updatable = false) Instant createdAt;
    protected JpaFavorite() { }

    public static class Key implements Serializable {
        public String userId;
        public Integer productId;
        public Key() { }
        @Override public boolean equals(Object value) {
            return value instanceof Key other && Objects.equals(userId, other.userId) && Objects.equals(productId, other.productId);
        }
        @Override public int hashCode() { return Objects.hash(userId, productId); }
    }
}
