package com.pubburi.rehearsal;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties="server.port=0")
@Import({JpaFavoriteIntegrationTests.Probes.class,JpaCartIntegrationTests.AckProbes.class})
@EnabledIfEnvironmentVariable(named="PUBBURI_JPA_INTEGRATION",matches="1")
class JpaCartIntegrationTests extends CartTestSupport {
    @Autowired JpaFavoriteIntegrationTests.Gate gate;
    @Autowired AckGate ack;
    @Test void accountCartAndIdempotentOperationsPreserveCurrentPricesDeletedItemsAndAccountLifetimes()throws Exception{
        var sql=control();var admin=loggedIn("ADMIN");String user=join(),other=join();int first=create(admin,ownName()),second=create(admin,ownName());
        try{
            var owner=login(user,password);var device=login(user,password);var stranger=login(other,password);
            assertEquals(401,cart(client(),user,"GET","",null).status());assertEquals(400,cart(owner,null,"GET","",null).status());assertEquals(401,cart(admin,user,"GET","",null).status());
            assertEquals(0,version(get(owner,user)));assertEquals(0,sql.queryForObject("SELECT count(*) FROM pubburi.t_user_cart WHERE user_id=?",Integer.class,user));
            var current=put(owner,user,first,3,0);assertEquals(1,version(current));assertEquals(current,get(device,user));String snapshot=line(current,first).path("productName").textValue();
            assertEquals(409,cart(device,user,"PUT","/items/"+first,Map.of("quantity",4,"expectedVersion",0)).status());
            assertEquals(409,cart(device,user,"DELETE","/items/"+first+"?expectedVersion=0",null).status());assertEquals(409,cart(device,user,"DELETE","?expectedVersion=0",null).status());
            assertEquals(409,cart(device,user,"POST","/merge",merge(UUID.randomUUID(),0,List.of(item(first,1)))).status());assertEquals(409,cart(device,user,"POST","/checkout",checkout(UUID.randomUUID(),0,List.of(item(first,1)))).status());assertEquals(current,get(owner,user));
            UUID merged=UUID.randomUUID();var intent=merge(merged,1,List.of(item(second,2),item(first,2)));var result=cart(owner,user,"POST","/merge",intent);assertEquals(200,result.status());assertFalse(result.body().at("/data/replayed").booleanValue());current=result.body().at("/data/cart");assertEquals(2,version(current));assertEquals(5,line(current,first).path("quantity").intValue());
            var replay=cart(device,user,"POST","/merge",merge(merged,0,List.of(item(first,2),item(second,2))));assertEquals(200,replay.status());assertTrue(replay.body().at("/data/replayed").booleanValue());assertEquals(current,replay.body().at("/data/cart"));
            var beforeRead=cartState(sql,user);var accountRead=accountState(sql,user);var read=cart(device,user,"GET","/operations/"+merged,null);assertEquals(200,read.status());assertTrue(read.body().at("/data/replayed").booleanValue());assertEquals(current,read.body().at("/data/cart"));assertEquals(beforeRead,cartState(sql,user));assertEquals(accountRead,accountState(sql,user));assertEquals(404,cart(stranger,other,"GET","/operations/"+merged,null).status());
            assertEquals(409,cart(owner,user,"POST","/merge",merge(merged,2,List.of(item(first,1)))).status());assertEquals(409,cart(owner,user,"POST","/checkout",checkout(merged,2,List.of(item(first,2),item(second,2)))).status());
            assertEquals(409,cart(owner,user,"POST","/merge",merge(UUID.randomUUID(),2,List.of(item(first,99),item(second,1)))).status());assertEquals(409,cart(owner,user,"POST","/merge",merge(UUID.randomUUID(),2,List.of(item(first,1),item(Integer.MAX_VALUE,1)))).status());assertEquals(current,get(owner,user));
            assertEquals(200,request(admin,"PUT","/api/admin/products/"+first,product(ownName(),1700)).status());var renamed=get(owner,user);assertEquals(snapshot,line(renamed,first).path("productName").textValue());assertEquals(1700,line(renamed,first).at("/currentProduct/price").intValue());assertEquals(2,version(renamed));
            UUID checked=UUID.randomUUID();var purchase=checkout(checked,2,List.of(item(first,2)));var purchased=cart(owner,user,"POST","/checkout",purchase);assertEquals(200,purchased.status());int order=purchased.body().at("/data/orderId").intValue();assertTrue(order>0);current=purchased.body().at("/data/cart");assertEquals(3,line(current,first).path("quantity").intValue());assertEquals(2,line(current,second).path("quantity").intValue());
            var history=request(owner,"GET","/api/orders/"+order,null);assertEquals(3400,history.body().at("/data/totalPrice").intValue());assertEquals(1,sql.queryForObject("SELECT count(*) FROM pubburi.t_order WHERE user_id=?",Integer.class,user));
            purchase.put("expectedVersion",0);var repeated=cart(device,user,"POST","/checkout",purchase);assertEquals(200,repeated.status());assertTrue(repeated.body().at("/data/replayed").booleanValue());assertEquals(order,repeated.body().at("/data/orderId").intValue());assertEquals(current,repeated.body().at("/data/cart"));
            assertEquals(200,request(admin,"DELETE","/api/admin/orders/"+order,null).status());assertEquals(order,cart(owner,user,"POST","/checkout",purchase).body().at("/data/orderId").intValue());
            assertEquals(order,cart(owner,user,"GET","/operations/"+checked,null).body().at("/data/orderId").intValue());
            var cleared=cart(owner,user,"DELETE","?expectedVersion="+version(current),null);assertEquals(200,cleared.status());current=cleared.body().path("data");assertTrue(current.path("items").isEmpty());long clearedVersion=version(current);
            assertTrue(cart(owner,user,"POST","/merge",intent).body().at("/data/cart/items").isEmpty());assertTrue(cart(owner,user,"POST","/checkout",purchase).body().at("/data/cart/items").isEmpty());assertEquals(clearedVersion,version(get(owner,user)));
            assertEquals(0,sql.queryForObject("SELECT count(*) FROM pubburi.t_order WHERE user_id=?",Integer.class,user));
            var independent=cart(stranger,other,"POST","/merge",merge(merged,0,List.of(item(first,1))));assertEquals(200,independent.status());assertFalse(independent.body().at("/data/replayed").booleanValue());assertEquals(0,get(owner,user).path("items").size());
            current=put(owner,user,second,1,clearedVersion);long beforeDelete=version(current);assertEquals(200,request(admin,"DELETE","/api/admin/products/"+second,null).status());var unavailable=get(owner,user);assertEquals(beforeDelete,version(unavailable));assertTrue(line(unavailable,second).path("currentProduct").isNull());assertEquals(line(current,second).path("productName"),line(unavailable,second).path("productName"));
            assertEquals(404,cart(owner,user,"PUT","/items/"+second,Map.of("quantity",2,"expectedVersion",beforeDelete)).status());assertEquals(409,cart(owner,user,"POST","/merge",merge(UUID.randomUUID(),beforeDelete,List.of(item(second,1)))).status());assertEquals(409,cart(owner,user,"POST","/checkout",checkout(UUID.randomUUID(),beforeDelete,List.of(item(second,1)))).status());
            var removed=cart(owner,user,"DELETE","/items/"+second+"?expectedVersion="+beforeDelete,null);assertEquals(200,removed.status());assertTrue(removed.body().at("/data/items").isEmpty());assertEquals(beforeDelete+1,removed.body().at("/data/version").longValue());
            assertEquals(200,request(admin,"DELETE","/api/admin/users/"+user,null).status());assertEquals(401,cart(owner,user,"GET","",null).status());assertEquals(201,request(client(),"POST","/api/users",registration(user)).status());var fresh=login(user,password);assertEquals(0,version(get(fresh,user)));assertTrue(get(fresh,user).path("items").isEmpty());
            for(String table:List.of("t_user_cart","t_user_cart_item","t_user_cart_operation"))assertEquals(0,sql.queryForObject("SELECT count(*) FROM pubburi."+table+" WHERE user_id=?",Integer.class,user));
            assertEquals(404,cart(fresh,user,"GET","/operations/"+merged,null).status());assertEquals(404,cart(fresh,user,"GET","/operations/"+checked,null).status());assertEquals(0,version(get(fresh,user)));
        }finally{clean(sql,user,first,List.of());clean(sql,other,second,List.of());}
    }
    @Test void concurrentRetryDoesNotDoubleMergeOrOrderAndRevokedEpochCannotMutate()throws Exception{
        var sql=control();var admin=loggedIn("ADMIN");String user=join();int product=create(admin,ownName());var executor=Executors.newFixedThreadPool(2);
        try{
            var one=login(user,password);var two=login(user,password);var initial=put(one,user,product,6,0);UUID merged=UUID.randomUUID();var payload=merge(merged,version(initial),List.of(item(product,2)));var start=new CountDownLatch(1);
            var a=executor.submit(()->{start.await();return cart(one,user,"POST","/merge",payload);});var b=executor.submit(()->{start.await();return cart(two,user,"POST","/merge",payload);});start.countDown();var first=a.get(15,TimeUnit.SECONDS);var second=b.get(15,TimeUnit.SECONDS);assertEquals(200,first.status());assertEquals(200,second.status());assertEquals(Set.of(true,false),Set.of(first.body().at("/data/replayed").booleanValue(),second.body().at("/data/replayed").booleanValue()));
            var current=get(one,user);assertEquals(8,line(current,product).path("quantity").intValue());assertEquals(2,version(current));
            var c=merge(UUID.randomUUID(),2,List.of(item(product,1)));var d=merge(UUID.randomUUID(),2,List.of(item(product,1)));var latch=new CountDownLatch(1);var ca=executor.submit(()->{latch.await();return cart(one,user,"POST","/merge",c).status();});var cb=executor.submit(()->{latch.await();return cart(two,user,"POST","/merge",d).status();});latch.countDown();assertEquals(Set.of(200,409),Set.of(ca.get(15,TimeUnit.SECONDS),cb.get(15,TimeUnit.SECONDS)));
            current=get(one,user);assertEquals(9,line(current,product).path("quantity").intValue());assertEquals(3,version(current));
            UUID checkout=UUID.randomUUID();var order=checkout(checkout,3,List.of(item(product,2)));var ready=new CountDownLatch(1);var oa=executor.submit(()->{ready.await();return cart(one,user,"POST","/checkout",order);});var ob=executor.submit(()->{ready.await();return cart(two,user,"POST","/checkout",order);});ready.countDown();var ra=oa.get(15,TimeUnit.SECONDS);var rb=ob.get(15,TimeUnit.SECONDS);assertEquals(200,ra.status());assertEquals(200,rb.status());assertEquals(ra.body().at("/data/orderId"),rb.body().at("/data/orderId"));assertEquals(1,sql.queryForObject("SELECT count(*) FROM pubburi.t_order WHERE user_id=?",Integer.class,user));
            current=get(one,user);assertEquals(7,line(current,product).path("quantity").intValue());current=put(two,user,product,12,version(current));order.put("expectedVersion",0);assertEquals(current,cart(one,user,"POST","/checkout",order).body().at("/data/cart"));
            var before=cartState(sql,user);
            try(var pause=gate.arm("write")){
                long revision=version(current);var late=executor.submit(()->cart(one,user,"PUT","/items/"+product,Map.of("quantity",10,"expectedVersion",revision)));assertTrue(pause.entered.await(4,TimeUnit.SECONDS));
                assertEquals(200,request(two,"PUT","/api/users/me/password",Map.of("currentPassword",password,"newPassword","Authored-cart-new-password")).status());pause.release.countDown();assertEquals(401,late.get(12,TimeUnit.SECONDS).status());
            }
            assertEquals(before,cartState(sql,user));
            for(String column:List.of("user_id","product_id","current_product_id","product_name"))assertThrows(DataAccessException.class,()->runtime.execute("UPDATE pubburi.t_user_cart_item SET "+column+"="+column+" WHERE false"));
            for(String table:List.of("t_user_cart","t_user_cart_operation"))assertThrows(DataAccessException.class,()->runtime.execute("DELETE FROM pubburi."+table+" WHERE false"));
            assertThrows(DataAccessException.class,()->runtime.execute("UPDATE pubburi.t_user_cart_operation SET intent_hash=intent_hash WHERE false"));
            assertThrows(DataAccessException.class,()->runtime.execute("TRUNCATE pubburi.t_user_cart_item"));
        }finally{executor.shutdownNow();assertTrue(executor.awaitTermination(3,TimeUnit.SECONDS));clean(sql,user,product,List.of());}
    }
    @Test void oneHundredRowsAndQuantityNinetyNineAreHardAtomicLimits()throws Exception{
        var sql=control();String user=join();String prefix=ownName();var products=new ArrayList<Integer>();
        try{
            products.addAll(sql.queryForList("INSERT INTO pubburi.t_product(name,type,price,img,abv,order_count) SELECT ?||n,'authored',1200,'authored.png',5,0 FROM generate_series(1,101) n RETURNING id",Integer.class,prefix));
            var owner=login(user,password);var items=products.subList(0,100).stream().map(id->item(id,1)).toList();var response=cart(owner,user,"POST","/merge",merge(UUID.randomUUID(),0,items));assertEquals(200,response.status());var before=get(owner,user);assertEquals(100,before.path("items").size());
            assertEquals(409,cart(owner,user,"PUT","/items/"+products.get(100),Map.of("quantity",1,"expectedVersion",version(before))).status());
            assertEquals(409,cart(owner,user,"POST","/merge",merge(UUID.randomUUID(),version(before),List.of(item(products.get(0),1),item(products.get(100),1)))).status());assertEquals(before,get(owner,user));
            var full=put(owner,user,products.get(0),99,version(before));assertEquals(409,cart(owner,user,"POST","/merge",merge(UUID.randomUUID(),version(full),List.of(item(products.get(0),1),item(products.get(1),1)))).status());assertEquals(full,get(owner,user));
        }finally{clean(sql,user,null,List.of());for(int product:products)sql.update("DELETE FROM pubburi.t_product WHERE id=? AND name LIKE ?",product,prefix+"%");}
    }
    @Test void responseTimeoutAfterCommitCanBeResolvedByReadWithoutRepeatingTheOrder()throws Exception{
        var sql=control();var admin=loggedIn("ADMIN");String user=join();int product=create(admin,ownName());
        try{
            var owner=login(user,password);var device=login(user,password);var current=put(owner,user,product,3,0);UUID operation=UUID.randomUUID();var payload=checkout(operation,version(current),List.of(item(product,2)));
            try(var pause=ack.arm(operation)){
                var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+PATH+"/checkout")).timeout(Duration.ofSeconds(2)).header("Content-Type","application/json").header(NotebookController.OWNER_HEADER,user).POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload))).build();
                var pending=owner.sendAsync(request,HttpResponse.BodyHandlers.ofString());assertTrue(pause.entered.await(4,TimeUnit.SECONDS));assertFalse(pending.isDone(),"The transaction must commit before the owned HTTP timeout");
                var error=assertThrows(ExecutionException.class,()->pending.get(4,TimeUnit.SECONDS));assertInstanceOf(HttpTimeoutException.class,error.getCause());
                var before=cartState(sql,user);var accountBefore=accountState(sql,user);var resolved=cart(device,user,"GET","/operations/"+operation,null);assertEquals(200,resolved.status());assertTrue(resolved.body().at("/data/replayed").booleanValue());assertEquals(1,line(resolved.body().at("/data/cart"),product).path("quantity").intValue());assertEquals(1,sql.queryForObject("SELECT count(*) FROM pubburi.t_order WHERE user_id=?",Integer.class,user));assertEquals(before,cartState(sql,user));assertEquals(accountBefore,accountState(sql,user));
                var retry=cart(device,user,"POST","/checkout",payload);assertEquals(200,retry.status());assertEquals(resolved.body().path("data"),retry.body().path("data"));
            }
        }finally{clean(sql,user,product,List.of());}
    }
    static final class AckPause implements AutoCloseable {
        final UUID operation;final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        AckPause(UUID operation){this.operation=operation;}@Override public void close(){release.countDown();}
    }
    static final class AckGate {
        final AtomicReference<AckPause> pending=new AtomicReference<>();
        AckPause arm(UUID operation){var pause=new AckPause(operation);assertTrue(pending.compareAndSet(null,pause));return pause;}
    }
    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods=false)
    static class AckProbes {
        @org.springframework.context.annotation.Bean AckGate cartAckGate(){return new AckGate();}
        @org.springframework.context.annotation.Bean AckAdvice cartAckAdvice(AckGate gate){return new AckAdvice(gate);}
    }
    @org.springframework.boot.test.context.TestComponent
    @org.springframework.web.bind.annotation.RestControllerAdvice
    static class AckAdvice implements org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice<Object> {
        final AckGate gate;AckAdvice(AckGate gate){this.gate=gate;}
        @Override public boolean supports(org.springframework.core.MethodParameter type,Class<? extends org.springframework.http.converter.HttpMessageConverter<?>> converter){return type.getDeclaringClass()==CartController.class;}
        @Override public Object beforeBodyWrite(Object body,org.springframework.core.MethodParameter type,org.springframework.http.MediaType media,Class<? extends org.springframework.http.converter.HttpMessageConverter<?>> converter,org.springframework.http.server.ServerHttpRequest request,org.springframework.http.server.ServerHttpResponse response){
            var pause=gate.pending.get();if(pause!=null && body instanceof com.pubburi.pub.controller.api.ApiResponse<?> envelope && envelope.data() instanceof CartService.Result result && pause.operation.equals(result.operationId()) && gate.pending.compareAndSet(pause,null)){
                // The controller and transactional service have returned: receipt/order/cart are committed, HTTP has not been written.
                pause.entered.countDown();try{if(!pause.release.await(6,TimeUnit.SECONDS))throw new IllegalStateException("Owned cart response gate deadline");}catch(InterruptedException error){Thread.currentThread().interrupt();throw new IllegalStateException(error);}
            }
            return body;
        }
    }
}
