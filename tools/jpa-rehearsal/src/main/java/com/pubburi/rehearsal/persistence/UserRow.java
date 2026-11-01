package com.pubburi.rehearsal.persistence;
import com.pubburi.pub.model.dto.User;
import java.util.UUID;
public record UserRow(String id,String name,String passwordHash,int stamps,String role,UUID epoch) {
    User dto(){User value=new User(id,name,passwordHash,stamps,role);value.setAuthEpoch(epoch.toString());return value;}
}
