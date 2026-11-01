package com.pubburi.rehearsal.persistence;
import com.pubburi.pub.model.dto.User;
import java.util.UUID;
public record SessionRow(String id,String name,int stamps,String role,UUID epoch) {
    User dto(){User value=new User(id,name,null,stamps,role);value.setAuthEpoch(epoch.toString());return value;}
}
