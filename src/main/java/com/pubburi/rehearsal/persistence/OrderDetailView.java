package com.pubburi.rehearsal.persistence;

import com.pubburi.pub.model.dto.OrderDetail;
import com.pubburi.pub.model.dto.OrderDetailInfo;

public record OrderDetailView(Integer id, int orderId, Integer productId, int quantity,
        String name, String type, String img, int unitPrice, int sumPrice) {
    OrderDetail dto() {
        var value = new OrderDetail(); value.setdId(id); value.setOrderId(orderId);
        value.setProductId(productId); value.setQuantity(quantity); value.setProductName(name);
        value.setProductType(type); value.setProductImg(img); value.setUnitPrice(unitPrice);
        value.setSumPrice(sumPrice); return value;
    }
    OrderDetailInfo info() {
        var value = new OrderDetailInfo(); value.setId(id); value.setOrderId(orderId);
        value.setProductId(productId); value.setQuantity(quantity); value.setName(name);
        value.setType(type); value.setImg(img); value.setUnitPrice(unitPrice);
        value.setSumPrice(sumPrice); return value;
    }
}
