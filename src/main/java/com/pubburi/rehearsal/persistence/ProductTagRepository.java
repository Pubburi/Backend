package com.pubburi.rehearsal.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductTagRepository extends JpaRepository<JpaProductTag, JpaProductTag.Key> {
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from RehearsalProductTag t where t.productId=:id")
    int deleteForProduct(@Param("id") int id);
}
