package com.pubburi.rehearsal.persistence;

import com.pubburi.pub.model.dto.UserPreferenceValue;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface PreferenceValueRepository extends JpaRepository<JpaPreferenceValue, JpaPreferenceValue.Key> {
    @Query("""
        select new com.pubburi.pub.model.dto.UserPreferenceValue(v.kind,v.value)
        from RehearsalPreferenceValue v join RehearsalUser u on u.id=v.userId
        where u.id=:userId and u.authEpoch=:epoch
        """)
    List<UserPreferenceValue> owned(@Param("userId") String userId, @Param("epoch") UUID epoch);
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from RehearsalPreferenceValue v where v.userId=:userId")
    int deleteForUser(@Param("userId") String userId);
}
