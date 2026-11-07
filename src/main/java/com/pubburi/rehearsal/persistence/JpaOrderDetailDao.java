package com.pubburi.rehearsal.persistence;

import com.pubburi.pub.model.dao.OrderDetailDao;
import com.pubburi.pub.model.dto.OrderDetail;
import jakarta.persistence.*;
import java.util.List;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional(readOnly = true)
public class JpaOrderDetailDao implements OrderDetailDao {
    private final OrderDetailRepository details;
    @PersistenceContext private EntityManager entityManager;
    public JpaOrderDetailDao(OrderDetailRepository details) { this.details = details; }
    @Override public List<OrderDetail> selectByOrderId(int orderId) {
        return entityManager.createQuery(JpaOrderDao.DETAIL_VIEW + " where d.orderId=:id order by d.id", OrderDetailView.class)
            .setParameter("id", orderId).getResultList().stream().map(OrderDetailView::dto).toList();
    }
    @Override @Transactional public int insert(OrderDetail detail) {
        var row = details.saveAndFlush(JpaOrderDetail.from(detail)); detail.setdId(row.id); return 1;
    }
    @Override @Transactional public int insertAll(List<OrderDetail> values) {
        var rows = values.stream().map(JpaOrderDetail::from).toList();
        details.saveAllAndFlush(rows); return rows.size();
    }
    @Override @Transactional public int deleteByOrderId(int orderId) {
        entityManager.flush();
        int count = entityManager.createQuery("delete from RehearsalOrderDetail d where d.orderId=:id")
            .setParameter("id", orderId).executeUpdate();
        entityManager.clear(); return count;
    }
}
