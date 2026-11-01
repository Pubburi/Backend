package com.pubburi.rehearsal.persistence;
import com.pubburi.pub.model.dto.Market;
public record MarketView(Integer id,double lat,double lng,String img,String name){Market dto(){return new Market(id,lat,lng,img,name);}}
