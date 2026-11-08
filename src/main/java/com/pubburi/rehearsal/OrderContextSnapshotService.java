package com.pubburi.rehearsal;

import java.util.*;
import com.pubburi.pub.controller.auth.SessionUser;
import com.pubburi.pub.controller.response.*;
import com.pubburi.pub.model.dao.*;
import com.pubburi.pub.model.service.OwnedContentSnapshotService;
import jakarta.persistence.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Order ownership, bounded original rows, current product facts and private hard/exclusion state share one RR. */
@Service
public class OrderContextSnapshotService {
    public record Snapshot(OrderResponse order,int referenceCount,Map<Integer,ProductResponse> currentProducts,OwnedContentSnapshotService.Snapshot content){ }
    private final UserDao users;private final OrderDao orders;private final ProductDao products;private final OwnedContentSnapshotService contents;
    @PersistenceContext private EntityManager em;
    public OrderContextSnapshotService(UserDao users,OrderDao orders,ProductDao products,OwnedContentSnapshotService contents){this.users=users;this.orders=orders;this.products=products;this.contents=contents;}
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Snapshot read(SessionUser owner,int orderId,List<Integer> ids,String namespace){
        if(users.selectSessionIdentity(owner.id(),owner.authEpoch())==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Account session is no longer current");
        var order=orders.selectOrderInfo(orderId);
        // Stored FK text can be a case/accent alias. The DB's returned canonical identity is authoritative.
        var recipient=order==null || order.getUserId()==null?null:users.selectSessionIdentity(order.getUserId(),owner.authEpoch());
        if(recipient==null || !owner.id().equals(recipient.getId()))throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Order not found");
        int count=Math.toIntExact(em.createQuery("select count(d) from RehearsalOrderDetail d where d.orderId=:id",Long.class).setParameter("id",orderId).getSingleResult());
        var current=new HashMap<Integer,ProductResponse>();
        if(count<=20){
            var details=orders.getOrderDetailInfo(orderId);if(details.size()!=count)throw new IllegalStateException("Order projections disagree");order.setDetails(details);
            var productIds=details.stream().map(row->row.getProductId()).filter(Objects::nonNull).distinct().toList();
            if(!productIds.isEmpty())for(var row:products.selectCurrentByIds(productIds))if(current.put(row.getId(),ResponseMapper.product(row))!=null)throw new IllegalStateException("Duplicate order product projection");
            for(var row:details)if(row.getId()<=0 || row.getQuantity()<1 || row.getProductId()!=null && !current.containsKey(row.getProductId()))throw new IllegalStateException("Invalid current order reference");
        }else order.setDetails(List.of());
        var content=namespace==null?contents.read(owner.id(),owner.authEpoch(),ids,List.of(),true):contents.read(owner.id(),owner.authEpoch(),ids,List.of(),true,namespace);
        return new Snapshot(ResponseMapper.order(order),count,Map.copyOf(current),content);
    }
}
