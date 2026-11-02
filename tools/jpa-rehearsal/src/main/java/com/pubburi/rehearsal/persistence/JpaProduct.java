package com.pubburi.rehearsal.persistence;

import jakarta.persistence.*;
import org.hibernate.annotations.Immutable;

/** Read-only mapping: this rehearsal does not expose product writes or outbox production. */
@Entity(name="RehearsalProduct")
@Table(name="t_product",schema="pubburi")
@Immutable
public class JpaProduct {
    @Id Integer id;
    @Column(length=100,nullable=false) String name;
    @Column(length=20,nullable=false) String type;
    @Column(nullable=false) int price;
    @Column(length=100,nullable=false) String img;
    @Column(nullable=false) float abv;
    @Column(name="order_count",nullable=false) int orderCount;
    protected JpaProduct() { }
}
