package com.pubburi.rehearsal.persistence;

import com.pubburi.pub.model.dao.OrderDao;
import com.pubburi.pub.model.dto.*;
import jakarta.persistence.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional(readOnly = true)
public class JpaOrderDao implements OrderDao {
    static final String ORDER_VIEW = "select new com.pubburi.rehearsal.persistence.OrderView(o.id,o.userId,o.orderType,o.orderTable,o.orderTime,o.completed,o.totalPrice,o.priceBasis) from RehearsalOrder o";
    static final String DETAIL_VIEW = "select new com.pubburi.rehearsal.persistence.OrderDetailView(d.id,d.orderId,d.productId,d.quantity,d.productName,d.productType,d.productImg,d.unitPrice,d.sumPrice) from RehearsalOrderDetail d";
    private final OrderRepository orders;
    @PersistenceContext private EntityManager entityManager;
    public JpaOrderDao(OrderRepository orders) { this.orders = orders; }

    private List<OrderView> page(String userId, int limit, int offset) {
        var query = entityManager.createQuery(ORDER_VIEW + (userId == null ? "" : " where o.userId=:userId") + " order by o.id desc", OrderView.class);
        if (userId != null) query.setParameter("userId", userId);
        return query.setFirstResult(offset).setMaxResults(limit).getResultList();
    }
    private OrderView find(int id) {
        var rows = entityManager.createQuery(ORDER_VIEW + " where o.id=:id", OrderView.class)
            .setParameter("id", id).setMaxResults(1).getResultList();
        return rows.isEmpty() ? null : rows.get(0);
    }
    @Override public List<Order> selectByUserId(String userId) { return userId == null ? List.of() : page(userId, Integer.MAX_VALUE, 0).stream().map(OrderView::dto).toList(); }
    @Override public Order selectById(int id) { var row = find(id); return row == null ? null : row.dto(); }
    @Override public OrderInfo selectOrderInfo(int id) { var row = find(id); return row == null ? null : row.info(); }
    @Override @Transactional public int insert(Order order) {
        var row = orders.saveAndFlush(JpaOrder.from(order)); order.setoId(row.id); return 1;
    }
    private int update(String hql, int id, Object value) {
        entityManager.flush();
        int count = entityManager.createQuery(hql).setParameter("id", id).setParameter("value", value).executeUpdate();
        entityManager.clear(); return count;
    }
    @Override @Transactional public int updateTotalPrice(int id, int totalPrice) {
        return update("update RehearsalOrder o set o.totalPrice=:value where o.id=:id and o.priceBasis='ORDER_SNAPSHOT'", id, totalPrice);
    }
    @Override @Transactional public int update(Order order) {
        return update("update RehearsalOrder o set o.completed=:value where o.id=:id", order.getoId(), order.getCompleted());
    }
    @Override @Transactional public int delete(int id) {
        // A single affected-row delete preserves the legacy 0/1 result even for competing deletes.
        return orders.remove(id);
    }
    @Override public List<OrderDetailInfo> getOrderDetailInfo(int orderId) { return getOrderDetailInfoByOrderIds(List.of(orderId)); }
    @Override public List<OrderDetailInfo> getOrderDetailInfoByOrderIds(List<Integer> ids) {
        if (ids.isEmpty()) return List.of();
        return entityManager.createQuery(DETAIL_VIEW + " where d.orderId in :ids order by d.orderId desc,d.id", OrderDetailView.class)
            .setParameter("ids", ids).getResultList().stream().map(OrderDetailView::info).toList();
    }
    @Override public List<OrderInfo> selectOrderInfoListByUserId(String userId) { return selectOrderInfoPageByUserId(userId, Integer.MAX_VALUE, 0); }
    @Override public List<OrderInfo> selectOrderInfoPageByUserId(String userId, int limit, int offset) { return userId == null ? List.of() : page(userId, limit, offset).stream().map(OrderView::info).toList(); }
    @Override public int countOrderInfoByUserId(String userId) {
        return Math.toIntExact(entityManager.createQuery("select count(o) from RehearsalOrder o where o.userId=:id", Long.class).setParameter("id", userId).getSingleResult());
    }
    @Override public List<OrderInfo> selectAllOrderInfo() { return selectAllOrderInfoPaged(Integer.MAX_VALUE, 0); }
    @Override public List<OrderInfo> selectAllOrderInfoPaged(int limit, int offset) { return page(null, limit, offset).stream().map(OrderView::info).toList(); }
    @Override public int countAllOrderInfo() { return Math.toIntExact(orders.count()); }
    private List<OrderInfo> recent(String id, int months) {
        // MySQL NOW() is statement time. PostgreSQL CURRENT_TIMESTAMP would freeze at an outer TX's start.
        // Keep this dialect-sensitive window explicit, without moving ordinary reads to native SQL.
        List<?> rows = entityManager.createNativeQuery("""
            SELECT o_id,user_id,order_type,order_table,order_time,completed,total_price,price_basis
            FROM pubburi.t_order WHERE user_id=?1
              AND order_time > statement_timestamp() - make_interval(months => ?2)
            ORDER BY o_id DESC
            """).setParameter(1, id).setParameter(2, months).getResultList();
        return rows.stream().map(value -> {
            Object[] row = (Object[]) value;
            Instant time = row[4] instanceof Instant instant ? instant
                : row[4] instanceof OffsetDateTime offset ? offset.toInstant() : ((Timestamp) row[4]).toInstant();
            return new OrderView(((Number) row[0]).intValue(), (String) row[1], (String) row[2],
                (String) row[3], time, row[5] == null ? null : row[5].toString(),
                row[6] == null ? null : ((Number) row[6]).intValue(), (String) row[7]).info();
        }).toList();
    }
    @Override public List<OrderInfo> getLastMonthOrder(String id) { return recent(id, 1); }
    @Override public List<OrderInfo> getLast6MonthOrder(String id) { return recent(id, 6); }
}
