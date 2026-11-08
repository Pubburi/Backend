package com.pubburi.rehearsal.persistence;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.io.Serializable;
import java.util.*;

@Entity @Table(name="t_user_cart_operation",schema="pubburi") @IdClass(JpaCartOperation.Key.class)
public class JpaCartOperation {
    public enum Kind { MERGE, CHECKOUT }
    public enum Status { APPLIED, RETIRED }
    @Id @Column(name="user_id",length=100,updatable=false) String userId;
    @Id @Column(name="operation_id",updatable=false) UUID operationId;
    @Enumerated(EnumType.STRING) @Column(length=8,nullable=false,updatable=false) Kind kind;
    @Enumerated(EnumType.STRING) @Column(length=7,nullable=false,updatable=false) Status status;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name="intent_hash",length=64,nullable=false,updatable=false) String intentHash;
    @Column(name="order_id",updatable=false) Integer orderId;
    protected JpaCartOperation(){ }
    public static JpaCartOperation create(String owner,UUID operation,Kind kind,String hash,Integer order){
        var row=new JpaCartOperation();row.userId=owner;row.operationId=operation;row.kind=kind;row.status=Status.APPLIED;row.intentHash=hash;row.orderId=order;return row;
    }
    public static JpaCartOperation retire(String owner,UUID operation,Kind kind,String hash){var row=create(owner,operation,kind,hash,null);row.status=Status.RETIRED;return row;}
    public Kind kind(){return kind;} public String intentHash(){return intentHash;} public Integer orderId(){return orderId;}
    public Status status(){return status;}
    public static class Key implements Serializable {
        public String userId;public UUID operationId;
        public Key(){ } public Key(String owner,UUID operation){userId=owner;operationId=operation;}
        @Override public boolean equals(Object value){return value instanceof Key key && Objects.equals(userId,key.userId) && Objects.equals(operationId,key.operationId);}
        @Override public int hashCode(){return Objects.hash(userId,operationId);}
    }
}
