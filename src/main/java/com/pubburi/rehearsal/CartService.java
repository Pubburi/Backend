package com.pubburi.rehearsal;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.pubburi.pub.controller.auth.SessionUser;
import com.pubburi.pub.controller.response.*;
import com.pubburi.pub.model.dao.*;
import com.pubburi.pub.model.dto.*;
import com.pubburi.pub.model.service.OrderService;
import com.pubburi.rehearsal.persistence.*;
import com.pubburi.rehearsal.persistence.JpaCartOperation.Kind;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@Service
public class CartService {
    private final UserDao users;private final ProductDao products;private final OrderService orders;
    private final CartRepository carts;private final CartItemRepository lines;private final CartOperationRepository operations;
    public CartService(UserDao users,ProductDao products,OrderService orders,CartRepository carts,CartItemRepository lines,CartOperationRepository operations){
        this.users=users;this.products=products;this.orders=orders;this.carts=carts;this.lines=lines;this.operations=operations;
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    @io.swagger.v3.oas.annotations.media.Schema(name="CartItem")
    public record Item(int productId,String productName,ProductResponse currentProduct,int quantity){ }
    @io.swagger.v3.oas.annotations.media.Schema(name="AccountCart")
    public record Cart(long version,List<Item> items){ }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    @io.swagger.v3.oas.annotations.media.Schema(name="CartOperationResult")
    public record Result(UUID operationId,boolean replayed,Cart cart,Integer orderId){ }
    private void current(SessionUser owner){if(users.selectSessionIdentity(owner.id(),owner.authEpoch())==null)throw expired();}
    private void lockOwner(SessionUser owner){var row=users.selectByIdForUpdate(owner.id());if(row==null || !owner.authEpoch().equals(row.getAuthEpoch()))throw expired();}
    private JpaCart locked(SessionUser owner){return carts.locked(owner.id()).orElseGet(()->carts.saveAndFlush(JpaCart.create(owner.id())));}
    private void expected(JpaCart cart,long version){if(cart.version()!=version)throw conflict("Cart changed; reload before saving");if(version==NotebookInput.MAX_VERSION)throw conflict("Cart revision limit reached");}
    private void advance(JpaCart cart){cart.touch();carts.saveAndFlush(cart);}
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Cart get(SessionUser owner){current(owner);return view(owner.id(),carts.findById(owner.id()).orElse(null));}
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Result operation(SessionUser owner,UUID operation){
        current(owner);var receipt=operations.findById(new JpaCartOperation.Key(owner.id(),operation)).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Cart operation not found"));
        return new Result(operation,true,view(owner.id(),carts.findById(owner.id()).orElseThrow()),receipt.orderId());
    }
    @Transactional
    public Cart put(SessionUser owner,int productId,CartInput.Quantity input){
        lockOwner(owner);var cart=locked(owner);expected(cart,input.expectedVersion());var all=lines.owned(owner.id());
        var product=products.selectByIdForUpdate(productId);if(product==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Product not found");
        var line=all.stream().filter(i->i.productId()==productId).findFirst().orElse(null);
        if(line==null){if(all.size()>=CartInput.MAX_ITEMS)throw conflict("Cart item limit reached");line=JpaCartItem.create(owner.id(),productId,product.getName(),input.quantity());}
        else{if(line.currentProductId()==null)throw conflict("Cart product is no longer available");line.quantity(input.quantity());}
        lines.saveAndFlush(line);advance(cart);return view(owner.id(),cart);
    }
    @Transactional
    public Cart remove(SessionUser owner,Integer productId,long version){
        lockOwner(owner);var cart=locked(owner);expected(cart,version);
        if(productId==null)lines.removeAll(owner.id());else lines.findById(new JpaCartItem.Key(owner.id(),productId)).ifPresent(lines::delete);
        lines.flush();advance(cart);return view(owner.id(),cart);
    }
    @Transactional
    public Result merge(SessionUser owner,CartInput.Merge input){
        lockOwner(owner);String hash=CartInput.intent(input.items(),null,null);var replay=replay(owner,input.operationId(),Kind.MERGE,hash);if(replay!=null)return replay;
        var cart=locked(owner);expected(cart,input.expectedVersion());var all=lines.owned(owner.id());var owned=new HashMap<Integer,JpaCartItem>();for(var row:all)owned.put(row.productId(),row);
        var current=lockProducts(input.items());int additions=0;
        // Validate every row before writing any quantity. Unavailable/over-limit input never partly merges.
        for(var item:input.items()){
            var row=owned.get(item.productId());if(row==null)additions++;
            else if(row.currentProductId()==null || row.quantity()+item.quantity()>CartInput.MAX_QUANTITY)throw conflict("Cart merge cannot preserve all quantities");
        }
        if(all.size()+additions>CartInput.MAX_ITEMS)throw conflict("Cart item limit reached");
        for(var item:input.items()){
            var row=owned.get(item.productId());if(row==null)row=JpaCartItem.create(owner.id(),item.productId(),current.get(item.productId()).getName(),item.quantity());else row.quantity(row.quantity()+item.quantity());
            lines.save(row);
        }
        lines.flush();advance(cart);operations.saveAndFlush(JpaCartOperation.create(owner.id(),input.operationId(),Kind.MERGE,hash,null));
        return new Result(input.operationId(),false,view(owner.id(),cart),null);
    }
    @Transactional
    public Result checkout(SessionUser owner,CartInput.Checkout input){
        lockOwner(owner);String hash=CartInput.intent(input.items(),input.orderType(),input.orderTable());var replay=replay(owner,input.operationId(),Kind.CHECKOUT,hash);if(replay!=null)return replay;
        var cart=locked(owner);expected(cart,input.expectedVersion());var owned=new HashMap<Integer,JpaCartItem>();for(var row:lines.owned(owner.id()))owned.put(row.productId(),row);
        for(var item:input.items()){
            var row=owned.get(item.productId());if(row==null || row.currentProductId()==null || row.quantity()<item.quantity())throw conflict("Cart checkout items changed");
        }
        lockProducts(input.items());
        var order=new Order();order.setUserId(owner.id());order.setOrderType(input.orderType());order.setOrderTable(input.orderTable());
        var details=input.items().stream().map(item->{var row=new OrderDetail();row.setProductId(item.productId());row.setQuantity(item.quantity());return row;}).toList();
        if(orders.makeOrder(order,details)!=1)throw new IllegalStateException("Cart order could not be created");
        for(var item:input.items()){var row=owned.get(item.productId());int remaining=row.quantity()-item.quantity();if(remaining==0)lines.delete(row);else row.quantity(remaining);}
        lines.flush();advance(cart);operations.saveAndFlush(JpaCartOperation.create(owner.id(),input.operationId(),Kind.CHECKOUT,hash,order.getoId()));
        return new Result(input.operationId(),false,view(owner.id(),cart),order.getoId());
    }
    private Result replay(SessionUser owner,UUID operation,Kind kind,String hash){
        var receipt=operations.findById(new JpaCartOperation.Key(owner.id(),operation)).orElse(null);if(receipt==null)return null;
        if(receipt.kind()!=kind || !receipt.intentHash().equals(hash))throw conflict("Operation ID is bound to a different intent");
        return new Result(operation,true,view(owner.id(),carts.findById(owner.id()).orElseThrow()),receipt.orderId());
    }
    private Map<Integer,Product> lockProducts(List<CartInput.Item> items){
        var result=new HashMap<Integer,Product>();for(var item:items.stream().sorted(Comparator.comparingInt(CartInput.Item::productId)).toList()){var product=products.selectByIdForUpdate(item.productId());if(product==null)throw conflict("Cart product is no longer available");result.put(item.productId(),product);}return result;
    }
    private Cart view(String owner,JpaCart cart){
        var rows=lines.owned(owner);var ids=rows.stream().map(JpaCartItem::currentProductId).filter(Objects::nonNull).distinct().toList();var current=new HashMap<Integer,ProductResponse>();
        if(!ids.isEmpty())for(var product:products.selectCurrentByIds(ids))current.put(product.getId(),ResponseMapper.product(product));
        return new Cart(cart==null?0:cart.version(),rows.stream().map(row->new Item(row.productId(),row.productName(),row.currentProductId()==null?null:current.get(row.currentProductId()),row.quantity())).toList());
    }
    private static ResponseStatusException conflict(String reason){return new ResponseStatusException(HttpStatus.CONFLICT,reason);}
    private static ResponseStatusException expired(){return new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Account session is no longer current");}
}
