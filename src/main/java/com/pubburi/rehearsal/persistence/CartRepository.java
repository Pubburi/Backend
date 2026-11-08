package com.pubburi.rehearsal.persistence;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface CartRepository extends JpaRepository<JpaCart,String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select c from JpaCart c where c.userId=:owner")
    Optional<JpaCart> locked(@Param("owner") String owner);
}
