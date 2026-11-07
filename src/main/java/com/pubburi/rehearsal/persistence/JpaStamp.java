package com.pubburi.rehearsal.persistence;

import com.pubburi.pub.model.dto.Stamp;
import jakarta.persistence.*;

@Entity(name = "RehearsalStamp")
@Table(name = "t_stamp", schema = "pubburi")
public class JpaStamp {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) Integer id;
    @Column(name = "user_id", length = 100, nullable = false) String userId;
    @Column(name = "order_id", nullable = false) int orderId;
    @Column(nullable = false) int quantity;
    protected JpaStamp() { }
    static JpaStamp from(Stamp stamp) {
        var row = new JpaStamp(); row.userId = stamp.getUserId();
        row.orderId = stamp.getOrderId(); row.quantity = stamp.getQuantity(); return row;
    }
}
