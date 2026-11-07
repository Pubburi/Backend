package com.pubburi.rehearsal.persistence;

import com.pubburi.pub.model.dao.UserDao;
import com.pubburi.pub.model.dto.User;
import jakarta.persistence.*;
import java.util.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional(readOnly=true)
public class JpaUserDao implements UserDao {
    private final UserRepository users;
    @PersistenceContext private EntityManager entityManager;
    public JpaUserDao(UserRepository users){this.users=users;}
    @Override public List<User> selectAll(){return page(Integer.MAX_VALUE,0);}
    @Override public List<User> selectPaged(int limit,int offset){return page(limit,offset);}
    private List<User> page(int limit,int offset){return entityManager.createQuery("select new com.pubburi.rehearsal.persistence.UserRow(u.id,u.name,u.passwordHash,u.stamps,u.role,u.authEpoch) from RehearsalUser u order by u.id",UserRow.class).setFirstResult(offset).setMaxResults(limit).getResultList().stream().map(UserRow::dto).toList();}
    @Override public int countAll(){return Math.toIntExact(users.count());}
    @Override @Transactional public int insert(User user){users.saveAndFlush(JpaUser.from(user));return 1;}
    private String lockId(String id){var rows=entityManager.createNativeQuery("SELECT id FROM pubburi.t_user WHERE id=:id FOR UPDATE",String.class).setParameter("id",id).getResultList();return rows.isEmpty()?null:(String)rows.get(0);}
    @Override @Transactional public int update(User user){String id=lockId(user.getId());if(id==null)return 0;JpaUser entity=entityManager.find(JpaUser.class,id);entityManager.refresh(entity);entity.apply(user);users.flush();return 1;}
    @Override @Transactional public int delete(String id){String actual=lockId(id);if(actual==null)return 0;JpaUser entity=entityManager.find(JpaUser.class,actual);users.delete(entity);users.flush();return 1;}
    @Override public User selectById(String id){return users.row(id).map(UserRow::dto).orElse(null);}
    @Override @Transactional public User selectByIdForUpdate(String id){String actual=lockId(id);return actual==null?null:users.row(actual).map(UserRow::dto).orElse(null);}
    @Override public User selectSessionIdentity(String id,String epoch){try{return users.identity(id,UUID.fromString(epoch)).map(SessionRow::dto).orElse(null);}catch(IllegalArgumentException invalid){return null;}}
    @Override @Transactional public int upgradePassword(String id,String expectedHash,String epoch,String pass){
        int count=entityManager.createNativeQuery("UPDATE pubburi.t_user SET password_hash=:pass WHERE id=:id AND auth_epoch=CAST(:epoch AS uuid) AND password_hash COLLATE \"C\"=:expected")
            .setParameter("pass",pass).setParameter("id",id).setParameter("epoch",epoch).setParameter("expected",expectedHash).executeUpdate();entityManager.clear();return count;
    }
    @Override @Transactional public int clearOrderNotes(String id){return entityManager.createNativeQuery("UPDATE pubburi.t_order SET order_table=NULL WHERE user_id=:id").setParameter("id",id).executeUpdate();}
    @Override @Transactional public int updateStamp(User user){int count=entityManager.createQuery("update RehearsalUser u set u.stamps=:stamps where u.id=:id").setParameter("stamps",user.getStamps()).setParameter("id",user.getId()).executeUpdate();entityManager.clear();return count;}
    @Override @Transactional public int incrementStamps(String id,int quantity){int count=entityManager.createQuery("update RehearsalUser u set u.stamps=u.stamps+:quantity where u.id=:id").setParameter("quantity",quantity).setParameter("id",id).executeUpdate();entityManager.clear();return count;}
}
