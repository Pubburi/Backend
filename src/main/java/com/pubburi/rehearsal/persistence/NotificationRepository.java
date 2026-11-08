package com.pubburi.rehearsal.persistence;

import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface NotificationRepository extends JpaRepository<JpaNotification,UUID> {
    @Query("select n from AccountNotification n where n.userId=:owner and n.id in :ids")
    List<JpaNotification> owned(@Param("owner") String owner,@Param("ids") List<UUID> ids);
    @Query("select count(n) from AccountNotification n where n.userId=:owner")
    long countOwned(@Param("owner") String owner);
    @Query("select count(n) from AccountNotification n where n.userId=:owner and n.readAt is null")
    long unread(@Param("owner") String owner);
    @Modifying(flushAutomatically=true,clearAutomatically=true)
    @Query(value="""
        UPDATE pubburi.t_user_notification SET read_at=GREATEST(statement_timestamp(),created_at)
        WHERE user_id=:owner AND id IN (:ids) AND read_at IS NULL
        """,nativeQuery=true)
    int markRead(@Param("owner") String owner,@Param("ids") List<UUID> ids);
}
