package com.pubburi.rehearsal.persistence;
import com.pubburi.pub.model.dao.MarketDao;
import com.pubburi.pub.model.dto.Market;
import jakarta.persistence.*;
import java.util.List;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
@Repository @Transactional(readOnly=true)
public class JpaMarketDao implements MarketDao {
    private final MarketRepository markets;@PersistenceContext private EntityManager entityManager;
    public JpaMarketDao(MarketRepository markets){this.markets=markets;}
    @Override public List<Market> selectAll(){return selectPaged(Integer.MAX_VALUE,0);}
    @Override public List<Market> selectPaged(int limit,int offset){return entityManager.createQuery("select new com.pubburi.rehearsal.persistence.MarketView(m.id,m.lat,m.lng,m.img,m.name) from RehearsalMarket m order by m.id",MarketView.class).setFirstResult(offset).setMaxResults(limit).getResultList().stream().map(MarketView::dto).toList();}
    @Override public int countAll(){return Math.toIntExact(markets.count());}
    @Override public Market selectById(int id){return markets.view(id).map(MarketView::dto).orElse(null);}
    @Override @Transactional public int insert(Market market){JpaMarket entity=markets.saveAndFlush(JpaMarket.from(market));market.setId(entity.id);return 1;}
    @Override @Transactional public int update(Market market){var row=markets.locked(market.getId());if(row.isEmpty())return 0;row.get().apply(market);markets.flush();return 1;}
    @Override @Transactional public int delete(int id){var row=markets.locked(id);if(row.isEmpty())return 0;markets.delete(row.get());markets.flush();return 1;}
}
