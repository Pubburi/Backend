package com.pubburi.rehearsal.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface FavoriteRepository extends JpaRepository<JpaFavorite, JpaFavorite.Key> {
    @Query("""
        select case when exists (select f.productId from RehearsalFavorite f
          where f.userId=u.id and f.productId=:productId) then true else false end
        from RehearsalUser u where u.id=:userId and u.authEpoch=:epoch
        """)
    Optional<Boolean> state(@Param("userId") String userId, @Param("epoch") UUID epoch, @Param("productId") int productId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
        INSERT INTO pubburi.t_user_favorite(user_id,product_id,created_at)
        VALUES(:userId,:productId,statement_timestamp())
        ON CONFLICT (user_id,product_id) DO NOTHING
        """, nativeQuery = true)
    int ensure(@Param("userId") String userId, @Param("productId") int productId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from RehearsalFavorite f where f.userId=:userId and f.productId=:productId")
    int remove(@Param("userId") String userId, @Param("productId") int productId);
}
