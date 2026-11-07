package com.pubburi.rehearsal.persistence;

import jakarta.persistence.LockModeType;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import com.pubburi.rehearsal.persistence.JpaNotebook.Experience;

public interface NotebookRepository extends JpaRepository<JpaNotebook,UUID> {
    @Query("select n from JpaNotebook n where n.userId=:owner and n.id=:id")
    Optional<JpaNotebook> owned(@Param("owner") String owner,@Param("id") UUID id);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select n from JpaNotebook n where n.userId=:owner and n.id=:id")
    Optional<JpaNotebook> locked(@Param("owner") String owner,@Param("id") UUID id);
    @Query("select n from JpaNotebook n where n.userId=:owner and n.productId=:product")
    Optional<JpaNotebook> product(@Param("owner") String owner,@Param("product") int product);
    @Query("select n from JpaNotebook n where n.userId=:owner and (:experience is null or n.experience=:experience)")
    Page<JpaNotebook> page(@Param("owner") String owner,@Param("experience") Experience experience,Pageable page);
}
