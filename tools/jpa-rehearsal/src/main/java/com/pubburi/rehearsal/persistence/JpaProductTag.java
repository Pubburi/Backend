package com.pubburi.rehearsal.persistence;

import java.io.Serializable;
import java.util.Objects;
import jakarta.persistence.*;
import com.pubburi.pub.model.dto.ProductFeatureTag;
import org.springframework.data.domain.Persistable;

@Entity(name="RehearsalProductTag")
@Table(name="t_product_feature_tag",schema="pubburi")
@IdClass(JpaProductTag.Key.class)
public class JpaProductTag implements Persistable<JpaProductTag.Key> {
    @Id @Column(name="product_id") Integer productId;
    @Id @Column(length=5) String kind;
    @Id @Column(length=40) String tag;
    @Transient private boolean fresh=true;
    protected JpaProductTag() { }
    static JpaProductTag from(int id,ProductFeatureTag tag) {var row=new JpaProductTag();row.productId=id;row.kind=tag.kind();row.tag=tag.tag();return row;}
    @Override public Key getId(){return new Key(productId,kind,tag);}
    @Override public boolean isNew(){return fresh;}
    @PostLoad @PostPersist void persisted(){fresh=false;}

    public static class Key implements Serializable {
        public Integer productId;
        public String kind;
        public String tag;
        public Key() { }
        public Key(Integer id,String kind,String tag){productId=id;this.kind=kind;this.tag=tag;}
        @Override public boolean equals(Object other) {
            return other instanceof Key key && Objects.equals(productId,key.productId)
                && Objects.equals(kind,key.kind) && Objects.equals(tag,key.tag);
        }
        @Override public int hashCode() { return Objects.hash(productId,kind,tag); }
    }
}
