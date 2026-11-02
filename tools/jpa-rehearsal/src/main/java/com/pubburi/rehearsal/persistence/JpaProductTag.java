package com.pubburi.rehearsal.persistence;

import java.io.Serializable;
import java.util.Objects;
import jakarta.persistence.*;
import org.hibernate.annotations.Immutable;

@Entity(name="RehearsalProductTag")
@Table(name="t_product_feature_tag",schema="pubburi")
@IdClass(JpaProductTag.Key.class)
@Immutable
public class JpaProductTag {
    @Id @Column(name="product_id") Integer productId;
    @Id @Column(length=5) String kind;
    @Id @Column(length=40) String tag;
    protected JpaProductTag() { }

    public static class Key implements Serializable {
        public Integer productId;
        public String kind;
        public String tag;
        public Key() { }
        @Override public boolean equals(Object other) {
            return other instanceof Key key && Objects.equals(productId,key.productId)
                && Objects.equals(kind,key.kind) && Objects.equals(tag,key.tag);
        }
        @Override public int hashCode() { return Objects.hash(productId,kind,tag); }
    }
}
