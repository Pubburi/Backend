package com.pubburi.rehearsal.persistence;

import jakarta.persistence.*;
import com.pubburi.pub.model.dto.ProductFeatures;
import org.springframework.data.domain.Persistable;

@Entity(name="RehearsalProductFeature")
@Table(name="t_product_feature",schema="pubburi")
public class JpaProductFeature implements Persistable<Integer> {
    @Id @Column(name="product_id") Integer productId;
    @Column(name="volume_ml") Integer volumeMl;
    @Column(length=2000) String description;
    @Column(name="taste_known",nullable=false) boolean tasteKnown;
    @Column(name="aroma_known",nullable=false) boolean aromaKnown;
    @Column(name="source_name",length=200) String sourceName;
    @Column(name="source_url",length=1000) String sourceUrl;
    @Column(name="source_note",length=1000) String sourceNote;
    @Transient private boolean fresh=true;
    protected JpaProductFeature() { }
    static JpaProductFeature from(ProductFeatures features) {var row=new JpaProductFeature();row.productId=features.getProductId();row.apply(features);return row;}
    void apply(ProductFeatures features) {
        volumeMl=features.getVolumeMl();description=features.getDescription();tasteKnown=features.isTasteKnown();aromaKnown=features.isAromaKnown();
        sourceName=features.getSourceName();sourceUrl=features.getSourceUrl();sourceNote=features.getSourceNote();
    }
    @Override public Integer getId(){return productId;}
    @Override public boolean isNew(){return fresh;}
    @PostLoad @PostPersist void persisted(){fresh=false;}
}
