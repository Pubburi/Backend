package com.pubburi.rehearsal.persistence;

import com.pubburi.pub.model.dao.FavoriteDao;
import com.pubburi.pub.model.dto.Product;
import jakarta.persistence.*;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional(readOnly = true)
public class JpaFavoriteDao implements FavoriteDao {
    private static final String OWNED = " from RehearsalFavorite f join RehearsalUser u on u.id=f.userId join RehearsalProduct p on p.id=f.productId where u.id=:userId and u.authEpoch=:epoch";
    private final FavoriteRepository favorites;
    @PersistenceContext private EntityManager entityManager;
    public JpaFavoriteDao(FavoriteRepository favorites) { this.favorites = favorites; }
    private UUID epoch(String value) { try { return UUID.fromString(value); } catch (IllegalArgumentException | NullPointerException invalid) { return null; } }
    @Override public Boolean selectOwnedState(String userId, String authEpoch, int productId) {
        UUID epoch = epoch(authEpoch); return epoch == null ? null : favorites.state(userId, epoch, productId).orElse(null);
    }
    @Override public List<Product> selectOwnedPage(String userId, String authEpoch, int limit, int offset) {
        UUID epoch = epoch(authEpoch); if (epoch == null) return List.of();
        return entityManager.createQuery("select new com.pubburi.pub.model.dto.Product(p.id,p.name,p.type,p.price,p.img,p.abv,p.orderCount)" + OWNED + " order by f.createdAt desc,f.productId desc", Product.class)
            .setParameter("userId", userId).setParameter("epoch", epoch).setFirstResult(offset).setMaxResults(limit).getResultList();
    }
    @Override public int countOwned(String userId, String authEpoch) {
        UUID epoch = epoch(authEpoch); if (epoch == null) return 0;
        return Math.toIntExact(entityManager.createQuery("select count(f)" + OWNED, Long.class).setParameter("userId", userId).setParameter("epoch", epoch).getSingleResult());
    }
    @Override @Transactional public int ensurePresent(String userId, int productId) { return favorites.ensure(userId, productId); }
    @Override @Transactional public int deleteOwned(String userId, int productId) { return favorites.remove(userId, productId); }
}
