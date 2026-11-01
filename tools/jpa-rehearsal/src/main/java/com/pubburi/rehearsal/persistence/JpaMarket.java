package com.pubburi.rehearsal.persistence;
import com.pubburi.pub.model.dto.Market;
import jakarta.persistence.*;
@Entity(name="RehearsalMarket") @Table(name="t_market",schema="pubburi")
public class JpaMarket {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) Integer id;
    @Column(nullable=false) double lat;
    @Column(nullable=false) double lng;
    @Column(length=200) String img;
    @Column(length=100,nullable=false) String name;
    protected JpaMarket(){ }
    static JpaMarket from(Market m){var e=new JpaMarket();e.apply(m);return e;}
    void apply(Market m){lat=m.getLatitude();lng=m.getLongitude();img=m.getImage();name=m.getName();}
}
