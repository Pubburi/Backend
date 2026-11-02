package com.pubburi.rehearsal.persistence;

import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface OrderRepository extends JpaRepository<JpaOrder, Integer> {
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from RehearsalOrder o where o.id=:id")
    int remove(@Param("id") int id);
}
