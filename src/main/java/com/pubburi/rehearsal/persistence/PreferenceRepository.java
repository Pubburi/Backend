package com.pubburi.rehearsal.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PreferenceRepository extends JpaRepository<JpaPreference, String> {
    @Query("""
        select new com.pubburi.rehearsal.persistence.PreferenceHeaderView(u.id,coalesce(p.surveyStatus,'NOT_STARTED'),
          coalesce(p.categoriesKnown,false),coalesce(p.tasteKnown,false),coalesce(p.aromaKnown,false),
          p.targetAbvPercent,p.minPriceKrw,p.maxPriceKrw,p.minAbvPercent,p.maxAbvPercent)
        from RehearsalUser u left join RehearsalPreference p on p.userId=u.id
        where u.id=:userId and u.authEpoch=:epoch
        """)
    Optional<PreferenceHeaderView> owned(@Param("userId") String userId, @Param("epoch") UUID epoch);
}
