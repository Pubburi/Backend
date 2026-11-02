package com.pubburi.rehearsal.persistence;

import com.pubburi.pub.model.dto.OrderDetail;
import jakarta.persistence.*;

@Entity(name = "RehearsalOrderDetail")
@Table(name = "t_order_detail", schema = "pubburi")
public class JpaOrderDetail {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) @Column(name = "d_id") Integer id;
    @Column(name = "order_id", nullable = false) int orderId;
    @Column(name = "product_id") Integer productId;
    @Column(nullable = false) int quantity;
    @Column(name = "product_name", length = 100, nullable = false) String productName;
    @Column(name = "product_type", length = 20, nullable = false) String productType;
    @Column(name = "product_img", length = 100, nullable = false) String productImg;
    @Column(name = "unit_price", nullable = false) int unitPrice;
    @Column(name = "sum_price", nullable = false) int sumPrice;

    protected JpaOrderDetail() { }
    static JpaOrderDetail from(OrderDetail detail) {
        var row = new JpaOrderDetail(); row.orderId = detail.getOrderId();
        row.productId = detail.getProductId(); row.quantity = detail.getQuantity();
        row.productName = detail.getProductName(); row.productType = detail.getProductType();
        row.productImg = detail.getProductImg(); row.unitPrice = detail.getUnitPrice();
        row.sumPrice = detail.getSumPrice(); return row;
    }
}
