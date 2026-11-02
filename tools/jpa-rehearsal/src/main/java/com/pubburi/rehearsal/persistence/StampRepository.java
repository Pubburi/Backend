package com.pubburi.rehearsal.persistence;

import com.pubburi.pub.model.dto.Stamp;
import java.util.List;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface StampRepository extends JpaRepository<JpaStamp, Integer> {
    @Query("select new com.pubburi.pub.model.dto.Stamp(s.id,s.userId,s.orderId,s.quantity) from RehearsalStamp s where s.userId=:userId order by s.id")
    List<Stamp> byUser(@Param("userId") String userId);
}
