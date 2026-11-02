package com.pubburi.rehearsal.persistence;

import com.pubburi.pub.model.dto.Order;
import com.pubburi.pub.model.dto.OrderInfo;
import java.sql.Timestamp;
import java.time.Instant;

public record OrderView(Integer id, String userId, String orderType, String orderTable,
        Instant orderTime, String completed, Integer totalPrice, String priceBasis) {
    Order dto() {
        var value = new Order(); value.setoId(id); value.setUserId(userId);
        value.setOrderType(orderType); value.setOrderTable(orderTable);
        value.setOrderTime(orderTime == null ? null : Timestamp.from(orderTime));
        value.setCompleted(completed); value.setTotalPrice(totalPrice); value.setPriceBasis(priceBasis);
        return value;
    }
    OrderInfo info() {
        var value = new OrderInfo(); value.setoId(id); value.setUserId(userId);
        value.setOrderType(orderType); value.setOrderTable(orderTable);
        value.setOrderTime(orderTime == null ? null : Timestamp.from(orderTime));
        value.setCompleted(completed); value.setTotalPrice(totalPrice); value.setPriceBasis(priceBasis);
        return value;
    }
}
