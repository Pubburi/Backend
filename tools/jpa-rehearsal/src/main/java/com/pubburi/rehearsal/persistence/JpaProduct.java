package com.pubburi.rehearsal.persistence;

import jakarta.persistence.*;
import com.pubburi.pub.model.dto.Product;

/** Existing product row; business services own transaction and outbox production. */
@Entity(name="RehearsalProduct")
@Table(name="t_product",schema="pubburi")
public class JpaProduct {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) Integer id;
    @Column(length=100,nullable=false) String name;
    @Column(length=20,nullable=false) String type;
    @Column(nullable=false) int price;
    @Column(length=100,nullable=false) String img;
    @Column(nullable=false) float abv;
    @Column(name="order_count",nullable=false) int orderCount;
    protected JpaProduct() { }
    static JpaProduct from(Product product) {var row=new JpaProduct();row.apply(product);return row;}
    void apply(Product product) {
        name=product.getName();type=product.getType();price=product.getPrice();img=product.getImg();
        abv=product.getAbv();orderCount=product.getOrderCount();
    }
}
