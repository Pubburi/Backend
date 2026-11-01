package com.pubburi.rehearsal.persistence;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
public interface MarketRepository extends JpaRepository<JpaMarket,Integer> {
    @Query("select new com.pubburi.rehearsal.persistence.MarketView(m.id,m.lat,m.lng,m.img,m.name) from RehearsalMarket m where m.id=:id")
    Optional<MarketView> view(@Param("id") int id);
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select m from RehearsalMarket m where m.id=:id")
    Optional<JpaMarket> locked(@Param("id") int id);
}
