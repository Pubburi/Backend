package com.pubburi.rehearsal.persistence;
import jakarta.persistence.LockModeType;
import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
public interface UserRepository extends JpaRepository<JpaUser,String> {
    @Query("select new com.pubburi.rehearsal.persistence.UserRow(u.id,u.name,u.passwordHash,u.stamps,u.role,u.authEpoch) from RehearsalUser u where u.id=:id")
    Optional<UserRow> row(@Param("id") String id);
    @Query("select new com.pubburi.rehearsal.persistence.SessionRow(u.id,u.name,u.stamps,u.role,u.authEpoch) from RehearsalUser u where u.id=:id and u.authEpoch=:epoch")
    Optional<SessionRow> identity(@Param("id") String id,@Param("epoch") UUID epoch);
}
