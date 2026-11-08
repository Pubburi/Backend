package com.pubburi.rehearsal;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties="server.port=0")
@Import(JpaFavoriteIntegrationTests.Probes.class)
@EnabledIfEnvironmentVariable(named="PUBBURI_JPA_INTEGRATION",matches="1")
class JpaNotificationIntegrationTests extends NotificationTestSupport {
    @Autowired JpaFavoriteIntegrationTests.Gate gate;
    @Test void atomicBusinessEventsPrivatePageReadAndDeletedTargetsSurviveUntilAccountDeletion()throws Exception{
        var sql=control();var admin=loggedIn("ADMIN");String user=join(),other=join();int product=create(admin,ownName());
        try{
            var owner=login(user,password);var stranger=login(other,password);assertEquals(0,page(owner,user).path("total").intValue());
            int order=order(owner,product),review=review(owner,product);String name=sql.queryForObject("SELECT name FROM pubburi.t_product WHERE id=?",String.class,product);
            assertEquals(403,request(stranger,"PATCH","/api/admin/orders/"+order+"/complete",null).status());
            assertEquals(403,request(stranger,"DELETE","/api/comments/"+review,null).status());assertEquals(0,page(owner,user).path("total").intValue());
            assertEquals(200,request(admin,"PATCH","/api/admin/orders/"+order+"/complete",null).status());
            var first=stored(sql,user);assertEquals(1,first.size());assertEquals(200,request(admin,"PATCH","/api/admin/orders/"+order+"/complete",null).status());assertEquals(first,stored(sql,user));
            assertEquals(200,request(admin,"DELETE","/api/comments/"+review,null).status());assertEquals(404,request(admin,"DELETE","/api/comments/"+review,null).status());
            var listing=page(owner,user);assertEquals(Set.of("items","page","size","total","totalPages","hasNext","unreadCount"),keys(listing));assertEquals(2,listing.path("unreadCount").intValue());
            var one=notification(owner,user,"GET","?size=1",null).body().path("data");assertEquals(1,one.path("items").size());assertEquals(2,one.path("unreadCount").intValue());assertTrue(one.path("hasNext").booleanValue());
            var removed=listing.at("/items/0");assertEquals("REVIEW_REMOVED",removed.path("type").textValue());assertEquals(name,removed.path("productName").textValue());assertTrue(removed.path("orderId").isNull());assertTrue(removed.path("readAt").isNull());assertTrue(removed.path("targetAvailable").booleanValue());
            String id=removed.path("id").textValue();assertEquals(0,page(stranger,other).path("total").intValue());
            int otherOrder=order(stranger,product);assertEquals(200,request(admin,"PATCH","/api/admin/orders/"+otherOrder+"/complete",null).status());String foreign=page(stranger,other).at("/items/0/id").textValue();
            var unmodified=stored(sql,user);assertEquals(404,notification(owner,user,"POST","/read",Map.of("ids",List.of(id,foreign))).status());assertEquals(unmodified,stored(sql,user));
            assertEquals(404,notification(admin,required("PUBBURI_JPA_ADMIN_ID"),"PUT","/"+id+"/read",null).status());
            assertEquals(404,notification(stranger,other,"PUT","/"+id+"/read",null).status());assertEquals(401,notification(owner,other,"GET","",null).status());
            var before=stored(sql,user);assertEquals(404,notification(owner,user,"POST","/read",Map.of("ids",List.of(id,UUID.randomUUID().toString()))).status());assertEquals(before,stored(sql,user));
            var read=notification(owner,user,"PUT","/"+id+"/read",null);assertEquals(200,read.status());long readAt=read.body().at("/data/items/0/readAt").longValue();assertTrue(readAt>=removed.path("createdAt").longValue());
            assertEquals(read.body(),notification(owner,user,"PUT","/"+id+"/read",null).body());
            var ids=new ArrayList<String>();for(var row:listing.path("items"))ids.add(row.path("id").textValue());var readAll=notification(owner,user,"POST","/read",Map.of("ids",ids));assertEquals(200,readAll.status());assertEquals(0,readAll.body().at("/data/unreadCount").intValue());
            assertEquals(200,request(admin,"DELETE","/api/admin/orders/"+order,null).status());assertEquals(200,request(admin,"DELETE","/api/admin/products/"+product,null).status());
            var retained=page(owner,user);assertEquals(2,retained.path("total").intValue());for(var row:retained.path("items"))assertFalse(row.path("targetAvailable").booleanValue());assertEquals(name,retained.at("/items/0/productName").textValue());
            assertEquals(200,request(admin,"DELETE","/api/admin/users/"+user,null).status());assertEquals(401,notification(owner,user,"GET","",null).status());assertTrue(stored(sql,user).isEmpty());
            assertEquals(201,request(client(),"POST","/api/users",registration(user)).status());var fresh=login(user,password);assertEquals(0,page(fresh,user).path("total").intValue());assertEquals(404,notification(fresh,user,"PUT","/"+id+"/read",null).status());
        }finally{clean(sql,user,product,List.of());clean(sql,other,null,List.of());}
    }
    @Test void queuedOldEpochAndDemotedAdministratorCannotReadOrProduceEventsAfterRevocation()throws Exception{
        var sql=control();var admin=loggedIn("ADMIN");String user=join(),actor=join();int product=create(admin,ownName());var pool=Executors.newSingleThreadExecutor();
        try{
            var owner=login(user,password);int order=order(owner,product);assertEquals(200,request(admin,"PATCH","/api/admin/orders/"+order+"/complete",null).status());String id=page(owner,user).at("/items/0/id").textValue();var before=stored(sql,user);
            try(var pause=gate.arm("userLock")){
                var pending=pool.submit(()->notification(owner,user,"PUT","/"+id+"/read",null));assertTrue(pause.entered.await(4,TimeUnit.SECONDS));
                assertEquals(200,request(admin,"PUT","/api/admin/users/"+user,Map.of("pass","Authored-notification-rotated")).status());pause.release.countDown();assertEquals(401,pending.get(10,TimeUnit.SECONDS).status());
            }
            assertEquals(before,stored(sql,user));assertEquals(401,notification(owner,user,"GET","",null).status());
            var fresh=login(user,"Authored-notification-rotated");int pendingOrder=order(fresh,product);
            assertEquals(200,request(admin,"PUT","/api/admin/users/"+actor,Map.of("role","ADMIN")).status());var promoted=login(actor,password);
            try(var pause=gate.arm("userLock")){
                var pending=pool.submit(()->request(promoted,"PATCH","/api/admin/orders/"+pendingOrder+"/complete",null));assertTrue(pause.entered.await(4,TimeUnit.SECONDS));
                assertEquals(200,request(admin,"PUT","/api/admin/users/"+actor,Map.of("role","USER")).status());pause.release.countDown();assertEquals(403,pending.get(10,TimeUnit.SECONDS).status());
            }
            assertEquals(before,stored(sql,user));assertEquals("N",sql.queryForObject("SELECT completed FROM pubburi.t_order WHERE o_id=?",String.class,pendingOrder));
        }finally{pool.shutdownNow();assertTrue(pool.awaitTermination(3,TimeUnit.SECONDS));clean(sql,user,product,List.of());clean(sql,actor,null,List.of());}
    }
    @Test void selfAndCascadeRemovalHistoricalCompletionAndConcurrentActionsDoNotDuplicateEvents()throws Exception{
        var sql=control();var admin=loggedIn("ADMIN");String user=join();int product=create(admin,ownName());var pool=Executors.newFixedThreadPool(2);
        try{
            var owner=login(user,password);int self=review(owner,product);assertEquals(200,request(owner,"DELETE","/api/comments/"+self,null).status());assertEquals(0,page(owner,user).path("total").intValue());
            int old=order(owner,product);sql.update("UPDATE pubburi.t_order SET completed='Y' WHERE o_id=?",old);assertEquals(200,request(admin,"PATCH","/api/admin/orders/"+old+"/complete",null).status());assertEquals(0,page(owner,user).path("total").intValue());
            int nullable=order(owner,product);sql.update("UPDATE pubburi.t_order SET completed=NULL WHERE o_id=?",nullable);assertEquals(200,request(admin,"PATCH","/api/admin/orders/"+nullable+"/complete",null).status());assertEquals(1,page(owner,user).path("total").intValue());
            int fresh=order(owner,product);var gate=new CountDownLatch(1);var a=pool.submit(()->{gate.await();return request(admin,"PATCH","/api/admin/orders/"+fresh+"/complete",null).status();});var b=pool.submit(()->{gate.await();return request(loggedIn("ADMIN"),"PATCH","/api/admin/orders/"+fresh+"/complete",null).status();});gate.countDown();assertEquals(200,a.get(15,TimeUnit.SECONDS));assertEquals(200,b.get(15,TimeUnit.SECONDS));
            assertEquals(1,sql.queryForObject("SELECT count(*) FROM pubburi.t_user_notification WHERE event_type='ORDER_COMPLETED' AND event_id=?",Integer.class,fresh));
            int remove=review(owner,product);var start=new CountDownLatch(1);var c=pool.submit(()->{start.await();return request(admin,"DELETE","/api/comments/"+remove,null).status();});var d=pool.submit(()->{start.await();return request(loggedIn("ADMIN"),"DELETE","/api/comments/"+remove,null).status();});start.countDown();assertEquals(Set.of(200,404),Set.of(c.get(15,TimeUnit.SECONDS),d.get(15,TimeUnit.SECONDS)));assertEquals(1,sql.queryForObject("SELECT count(*) FROM pubburi.t_user_notification WHERE event_type='REVIEW_REMOVED' AND event_id=?",Integer.class,remove));
            int cascade=review(owner,product);var count=page(owner,user).path("total").intValue();assertEquals(200,request(admin,"DELETE","/api/admin/products/"+product,null).status());assertEquals(count,page(owner,user).path("total").intValue());assertEquals(0,sql.queryForObject("SELECT count(*) FROM pubburi.t_comment WHERE id=?",Integer.class,cascade));
            var first=page(owner,user).at("/items/0/id").textValue();var ready=new CountDownLatch(1);var x=pool.submit(()->{ready.await();return notification(owner,user,"PUT","/"+first+"/read",null);});var y=pool.submit(()->{ready.await();return notification(login(user,password),user,"PUT","/"+first+"/read",null);});ready.countDown();assertEquals(x.get(15,TimeUnit.SECONDS).body(),y.get(15,TimeUnit.SECONDS).body());
            for(String column:List.of("id","user_id","event_type","event_id","order_id","product_id","product_name","created_at"))assertThrows(DataAccessException.class,()->runtime.execute("UPDATE pubburi.t_user_notification SET "+column+"="+column+" WHERE false"));
            assertThrows(DataAccessException.class,()->runtime.execute("DELETE FROM pubburi.t_user_notification WHERE false"));assertThrows(DataAccessException.class,()->runtime.execute("TRUNCATE pubburi.t_user_notification"));
        }finally{pool.shutdownNow();clean(sql,user,product,List.of());}
    }
}
