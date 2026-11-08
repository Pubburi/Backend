package com.pubburi.rehearsal;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.pubburi.pub.controller.api.*;
import com.pubburi.pub.controller.auth.SessionUser;
import com.pubburi.pub.model.dao.*;
import com.pubburi.pub.model.dto.User;
import com.pubburi.pub.model.service.*;
import com.pubburi.rehearsal.persistence.*;
import jakarta.persistence.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@Service
public class NotificationService implements AccountNotificationActions {
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Entry(UUID id,JpaNotification.Type type,long createdAt,Long readAt,Integer orderId,
            Integer productId,String productName,boolean targetAvailable){ }
    public record Page(List<Entry> items,int page,int size,int total,int totalPages,boolean hasNext,int unreadCount){ }
    public record ReadItem(UUID id,long readAt){ }
    public record ReadResult(List<ReadItem> items,int unreadCount){ }
    private final NotificationRepository notifications;
    private final UserDao users;private final OrderDao orders;private final CommentDao comments;private final ProductDao products;
    private final OrderService orderService;private final CommentService commentService;
    @PersistenceContext private EntityManager em;
    public NotificationService(NotificationRepository notifications,UserDao users,OrderDao orders,CommentDao comments,
            ProductDao products,OrderService orderService,CommentService commentService){
        this.notifications=notifications;this.users=users;this.orders=orders;this.comments=comments;
        this.products=products;this.orderService=orderService;this.commentService=commentService;
    }
    private void current(SessionUser actor){if(users.selectSessionIdentity(actor.id(),actor.authEpoch())==null)throw expired();}
    private User lockActor(SessionUser actor,String recipient){
        var ids=new TreeSet<String>();ids.add(actor.id());if(recipient!=null)ids.add(recipient);User live=null;
        for(String id:ids){var user=users.selectByIdForUpdate(id);if(id.equals(actor.id()))live=user;}
        if(live==null || !actor.authEpoch().equals(live.getAuthEpoch()))throw expired();return live;
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Page page(SessionUser actor,PageCriteria criteria){
        current(actor);
        var rows=em.createQuery("select n from AccountNotification n where n.userId=:owner order by n.createdAt desc,n.id desc",JpaNotification.class)
            .setParameter("owner",actor.id()).setFirstResult(criteria.offset()).setMaxResults(criteria.size()).getResultList();
        var result=PageResponse.of(entries(actor.id(),rows),criteria,Math.toIntExact(notifications.countOwned(actor.id())));
        return new Page(result.items(),result.page(),result.size(),result.total(),result.totalPages(),result.hasNext(),Math.toIntExact(notifications.unread(actor.id())));
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public ReadResult read(SessionUser actor,List<UUID> ids){
        lockActor(actor,null);
        if(notifications.owned(actor.id(),ids).size()!=ids.size())throw missing();
        notifications.markRead(actor.id(),ids);
        var rows=new HashMap<UUID,JpaNotification>();for(var row:notifications.owned(actor.id(),ids))rows.put(row.id(),row);
        return new ReadResult(ids.stream().map(id->new ReadItem(id,Objects.requireNonNull(rows.get(id).readAt()))).toList(),Math.toIntExact(notifications.unread(actor.id())));
    }
    @Override @Transactional(isolation=Isolation.READ_COMMITTED)
    public void completeOrder(int id,SessionUser actor){
        var initial=orders.selectById(id);if(initial==null)throw missing();
        var live=lockActor(actor,initial.getUserId());if(!live.isAdmin())throw forbidden();
        if(!lockResource("t_order","o_id",id))throw missing();
        var order=orders.selectById(id);if(order==null)throw missing();
        if("Y".equals(order.getCompleted()))return;
        order.setCompleted("Y");if(orderService.updateOrder(order)!=1)throw new IllegalStateException("Order completion failed");
        if(order.getUserId()!=null)notifications.saveAndFlush(JpaNotification.completed(order.getUserId(),id));
    }
    @Override @Transactional(isolation=Isolation.READ_COMMITTED)
    public void removeComment(int id,SessionUser actor){
        var initial=comments.select(id);if(initial==null)throw missing();
        var live=lockActor(actor,initial.getUserId());
        if(!lockResource("t_comment","id",id))throw missing();
        var comment=comments.select(id);if(comment==null)throw missing();
        if(!live.isAdmin() && !live.getId().equals(comment.getUserId()))throw forbidden();
        var product=products.selectById(comment.getProductId());if(product==null)throw new IllegalStateException("Review product missing");
        if(commentService.removeComment(id)!=1)throw new IllegalStateException("Review removal failed");
        if(!live.getId().equals(comment.getUserId()))notifications.saveAndFlush(JpaNotification.removed(comment.getUserId(),id,comment.getProductId(),product.getName()));
    }
    private boolean lockResource(String table,String column,int id){
        // Identifiers are internal constants from the two explicit callers, never request input.
        return !em.createNativeQuery("SELECT "+column+" FROM pubburi."+table+" WHERE "+column+"=:id FOR UPDATE")
            .setParameter("id",id).getResultList().isEmpty();
    }
    private List<Entry> entries(String owner,List<JpaNotification> rows){
        var orderIds=rows.stream().map(JpaNotification::orderId).filter(Objects::nonNull).distinct().toList();
        var productIds=rows.stream().map(JpaNotification::productId).filter(Objects::nonNull).distinct().toList();
        var availableOrders=orderIds.isEmpty()?Set.<Integer>of():new HashSet<>(em.createQuery("select o.id from RehearsalOrder o where o.userId=:owner and o.id in :ids",Integer.class).setParameter("owner",owner).setParameter("ids",orderIds).getResultList());
        var availableProducts=new HashSet<Integer>();if(!productIds.isEmpty())for(var product:products.selectCurrentByIds(productIds))availableProducts.add(product.getId());
        return rows.stream().map(row->new Entry(row.id(),row.type(),row.createdAt(),row.readAt(),row.orderId(),row.productId(),row.productName(),
            row.type()==JpaNotification.Type.ORDER_COMPLETED?availableOrders.contains(row.orderId()):availableProducts.contains(row.productId()))).toList();
    }
    private static ResponseStatusException missing(){return new ResponseStatusException(HttpStatus.NOT_FOUND,"Notification or business record not found");}
    private static ResponseStatusException expired(){return new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Account session is no longer current");}
    private static ResponseStatusException forbidden(){return new ResponseStatusException(HttpStatus.FORBIDDEN,"Access denied");}
}
