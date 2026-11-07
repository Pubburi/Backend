package com.pubburi.rehearsal.persistence;

import com.pubburi.pub.model.dto.Order;
import jakarta.persistence.*;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;
import org.hibernate.type.SqlTypes;

@Entity(name = "RehearsalOrder")
@Table(name = "t_order", schema = "pubburi")
public class JpaOrder {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) @Column(name = "o_id") Integer id;
    @Column(name = "user_id", length = 100) String userId;
    @Column(name = "order_type", length = 10, nullable = false) String orderType;
    @Column(name = "order_table", length = 20) String orderTable;
    @Generated(event = EventType.INSERT, sql = "statement_timestamp()")
    @Column(name = "order_time", updatable = false) Instant orderTime;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(length = 1) String completed;
    @Column(name = "total_price") Integer totalPrice;
    @Column(name = "price_basis", length = 20, nullable = false) String priceBasis;

    protected JpaOrder() { }
    static JpaOrder from(Order order) {
        var row = new JpaOrder();
        row.userId = order.getUserId(); row.orderType = order.getOrderType();
        row.orderTable = order.getOrderTable(); row.completed = order.getCompleted();
        row.totalPrice = order.getTotalPrice(); row.priceBasis = order.getPriceBasis();
        return row;
    }
}
