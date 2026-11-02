package com.pubburi.rehearsal.persistence;

import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface CommentRepository extends JpaRepository<JpaComment, Integer> {
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update RehearsalComment c set c.rating=:rating,c.comment=:body where c.id=:id")
    int change(@Param("id") int id, @Param("rating") float rating, @Param("body") String body);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from RehearsalComment c where c.id=:id")
    int remove(@Param("id") int id);
}
