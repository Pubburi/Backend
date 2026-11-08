package com.pubburi.rehearsal.persistence;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Entity @Table(name="t_user_cart",schema="pubburi")
public class JpaCart {
    @Id @Column(name="user_id",length=100,updatable=false) String userId;
    @Version @Column(nullable=false) Long version;
    @Column(name="updated_at",nullable=false) Instant updatedAt;
    protected JpaCart(){ }
    public static JpaCart create(String owner){var row=new JpaCart();row.userId=owner;row.updatedAt=Instant.now().truncatedTo(ChronoUnit.MICROS);return row;}
    public Long version(){return version;}
    public void touch(){
        // Every accepted mutation advances the cart revision, including a no-op clear/absolute PUT.
        Instant now=Instant.now().truncatedTo(ChronoUnit.MICROS);
        updatedAt=now.isAfter(updatedAt)?now:updatedAt.plusNanos(1000);
    }
}
