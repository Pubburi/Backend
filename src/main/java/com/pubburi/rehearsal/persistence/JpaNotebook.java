package com.pubburi.rehearsal.persistence;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Entity
@Table(name="t_user_notebook",schema="pubburi")
public class JpaNotebook {
    public enum Experience { TRIED, INTERESTED }
    @Id UUID id;
    @Column(name="user_id",length=100,nullable=false,updatable=false) String userId;
    @Column(name="product_id",updatable=false) Integer productId;
    @Column(name="product_name",length=100,nullable=false,updatable=false) String productName;
    @Enumerated(EnumType.STRING) @Column(length=10,nullable=false) Experience experience;
    @Column(precision=3,scale=2) BigDecimal rating;
    @JdbcTypeCode(SqlTypes.ARRAY) @Column(name="taste_tags",columnDefinition="varchar(40)[]") String[] tasteTags;
    @JdbcTypeCode(SqlTypes.ARRAY) @Column(name="aroma_tags",columnDefinition="varchar(40)[]") String[] aromaTags;
    @Column(length=2000) String memo;
    @Version @Column(nullable=false) Long version;
    @Column(name="created_at",nullable=false,updatable=false) Instant createdAt;
    @Column(name="updated_at",nullable=false) Instant updatedAt;
    protected JpaNotebook() { }
    public static JpaNotebook create(String owner,int product,String name,Experience experience,BigDecimal rating,List<String> taste,List<String> aroma,String memo){
        var note=new JpaNotebook();note.id=UUID.randomUUID();note.userId=owner;note.productId=product;note.productName=name;
        note.createdAt=Instant.now().truncatedTo(ChronoUnit.MICROS);note.change(experience,rating,taste,aroma,memo);return note;
    }
    public void change(Experience value,BigDecimal score,List<String> taste,List<String> aroma,String text){
        experience=value;rating=score;tasteTags=taste==null?null:taste.toArray(String[]::new);aromaTags=aroma==null?null:aroma.toArray(String[]::new);memo=text;
        updatedAt=Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
    public UUID id(){return id;} public Integer productId(){return productId;} public String productName(){return productName;}
    public Experience experience(){return experience;} public BigDecimal rating(){return rating;}
    public List<String> tasteTags(){return tasteTags==null?null:List.of(tasteTags);} public List<String> aromaTags(){return aromaTags==null?null:List.of(aromaTags);}
    public String memo(){return memo;} public long version(){return version;} public long createdAt(){return createdAt.toEpochMilli();} public long updatedAt(){return updatedAt.toEpochMilli();}
}
