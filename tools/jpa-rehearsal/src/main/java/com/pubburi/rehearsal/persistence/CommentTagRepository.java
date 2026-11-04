package com.pubburi.rehearsal.persistence;

import java.util.List;
import com.pubburi.pub.model.dto.CommentTasteTag;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CommentTagRepository extends JpaRepository<JpaCommentTag, JpaCommentTag.Key> {
    @Query("select new com.pubburi.pub.model.dto.CommentTasteTag(t.commentId,t.tag) from RehearsalCommentTag t where t.commentId in :ids")
    List<CommentTasteTag> opinions(@Param("ids") List<Integer> ids);
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from RehearsalCommentTag t where t.commentId=:id")
    int deleteForComment(@Param("id") int id);
}
