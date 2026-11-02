package com.pubburi.rehearsal.persistence;

import jakarta.persistence.*;
import org.hibernate.annotations.Immutable;

@Entity(name="RehearsalProductFeature")
@Table(name="t_product_feature",schema="pubburi")
@Immutable
public class JpaProductFeature {
    @Id @Column(name="product_id") Integer productId;
    @Column(name="volume_ml") Integer volumeMl;
    @Column(length=2000) String description;
    @Column(name="taste_known",nullable=false) boolean tasteKnown;
    @Column(name="aroma_known",nullable=false) boolean aromaKnown;
    @Column(name="source_name",length=200) String sourceName;
    @Column(name="source_url",length=1000) String sourceUrl;
    @Column(name="source_note",length=1000) String sourceNote;
    protected JpaProductFeature() { }
}
