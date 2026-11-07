package com.pubburi.rehearsal.persistence;

import com.pubburi.pub.model.dto.User;
import jakarta.persistence.*;
import java.util.UUID;

@Entity(name="RehearsalUser")
@Table(name="t_user",schema="pubburi")
public class JpaUser implements org.springframework.data.domain.Persistable<String> {
    @Transient private boolean newEntity=true;
    @Override public String getId(){return id;}
    @Override @Transient public boolean isNew(){return newEntity;}
    @PostPersist @PostLoad void loaded(){newEntity=false;}
    @Id @Column(length=100,nullable=false) String id;
    @Column(length=100,nullable=false) String name;
    @Column(name="password_hash",length=100,nullable=false) String passwordHash;
    @Column(nullable=false) int stamps;
    @Column(length=20,nullable=false) String role;
    @Column(name="auth_epoch",nullable=false) UUID authEpoch;
    protected JpaUser(){ }
    static JpaUser from(User user){JpaUser entity=new JpaUser();entity.id=user.getId();entity.apply(user);return entity;}
    void apply(User user){name=user.getName();passwordHash=user.getPass();stamps=user.getStamps();role=user.getRole();authEpoch=UUID.fromString(user.getAuthEpoch());}
    User dto(){User user=new User(id,name,passwordHash,stamps,role);user.setAuthEpoch(authEpoch.toString());return user;}
}
