package com.pubburi.rehearsal.persistence;

import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface CartItemRepository extends JpaRepository<JpaCartItem,JpaCartItem.Key> {
    @Query("select i from JpaCartItem i where i.userId=:owner order by i.productId")
    List<JpaCartItem> owned(@Param("owner") String owner);
    @Modifying @Query("delete from JpaCartItem i where i.userId=:owner")
    int removeAll(@Param("owner") String owner);
}
