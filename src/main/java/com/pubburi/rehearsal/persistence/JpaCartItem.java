package com.pubburi.rehearsal.persistence;

import jakarta.persistence.*;
import java.io.Serializable;
import java.util.Objects;

@Entity @Table(name="t_user_cart_item",schema="pubburi") @IdClass(JpaCartItem.Key.class)
public class JpaCartItem {
    @Id @Column(name="user_id",length=100,updatable=false) String userId;
    @Id @Column(name="product_id",updatable=false) Integer productId;
    @Column(name="current_product_id",updatable=false) Integer currentProductId;
    @Column(name="product_name",length=100,nullable=false,updatable=false) String productName;
    @Column(nullable=false) Integer quantity;
    protected JpaCartItem(){ }
    public static JpaCartItem create(String owner,int product,String name,int quantity){
        var row=new JpaCartItem();row.userId=owner;row.productId=product;row.currentProductId=product;row.productName=name;row.quantity=quantity;return row;
    }
    public int productId(){return productId;} public Integer currentProductId(){return currentProductId;}
    public String productName(){return productName;} public int quantity(){return quantity;} public void quantity(int value){quantity=value;}
    public static class Key implements Serializable {
        public String userId;public Integer productId;
        public Key(){ } public Key(String owner,int product){userId=owner;productId=product;}
        @Override public boolean equals(Object value){return value instanceof Key key && Objects.equals(userId,key.userId) && Objects.equals(productId,key.productId);}
        @Override public int hashCode(){return Objects.hash(userId,productId);}
    }
}
