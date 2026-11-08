package com.pubburi.rehearsal.persistence;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

@Entity(name="AccountNotification")
@Table(name="t_user_notification",schema="pubburi")
public class JpaNotification {
    public enum Type { ORDER_COMPLETED, REVIEW_REMOVED }
    @Id UUID id;
    @Column(name="user_id",length=100,nullable=false,updatable=false) String userId;
    @Enumerated(EnumType.STRING) @Column(name="event_type",length=16,nullable=false,updatable=false) Type type;
    @Column(name="event_id",nullable=false,updatable=false) int eventId;
    @Column(name="order_id",updatable=false) Integer orderId;
    @Column(name="product_id",updatable=false) Integer productId;
    @Column(name="product_name",length=100,updatable=false) String productName;
    @Generated(event=EventType.INSERT,sql="statement_timestamp()")
    @Column(name="created_at",nullable=false,updatable=false) Instant createdAt;
    @Column(name="read_at") Instant readAt;
    protected JpaNotification(){ }
    public static JpaNotification completed(String owner,int orderId){
        var row=create(owner,Type.ORDER_COMPLETED,orderId);row.orderId=orderId;return row;
    }
    public static JpaNotification removed(String owner,int commentId,int productId,String name){
        var row=create(owner,Type.REVIEW_REMOVED,commentId);row.productId=productId;row.productName=name;return row;
    }
    private static JpaNotification create(String owner,Type type,int eventId){
        var row=new JpaNotification();row.id=UUID.randomUUID();row.userId=owner;row.type=type;row.eventId=eventId;return row;
    }
    public UUID id(){return id;} public Type type(){return type;}
    public Integer orderId(){return orderId;} public Integer productId(){return productId;}
    public String productName(){return productName;}
    public long createdAt(){return createdAt.toEpochMilli();}
    public Long readAt(){return readAt==null?null:readAt.toEpochMilli();}
}
