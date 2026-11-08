package com.pubburi.rehearsal;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties="server.port=0")
@EnabledIfEnvironmentVariable(named="PUBBURI_JPA_INTEGRATION",matches="1")
class JpaNotificationCanonicalOwnerIntegrationTests extends NotificationTestSupport {
    @Test void memberCanDeleteOwnReviewWhosePreservedForeignKeyHasEquivalentCase()throws Exception{self(false);}
    @Test void administratorDeletingOwnEquivalentCaseReviewCreatesNoNotification()throws Exception{self(true);}
    @Test void completingAnOrderAfterItsAccountDeletionNeverNotifiesTheRejoinedId()throws Exception{
        var sql=control();var admin=loggedIn("ADMIN");String user=join();int product=create(admin,ownName());Integer id=null;
        try{
            var owner=login(user,password);id=order(owner,product);assertEquals(200,request(admin,"DELETE","/api/admin/users/"+user,null).status());
            assertEquals(200,request(admin,"PATCH","/api/admin/orders/"+id+"/complete",null).status());assertEquals("Y",sql.queryForObject("SELECT completed FROM pubburi.t_order WHERE o_id=?",String.class,id));
            assertNull(sql.queryForObject("SELECT user_id FROM pubburi.t_order WHERE o_id=?",String.class,id));assertTrue(stored(sql,user).isEmpty());
            assertEquals(201,request(client(),"POST","/api/users",registration(user)).status());var fresh=login(user,password);
            assertEquals(200,request(admin,"PATCH","/api/admin/orders/"+id+"/complete",null).status());assertEquals(0,page(fresh,user).path("total").intValue());
        }finally{clean(sql,user,product,id==null?List.of():List.of(id));}
    }
    @Test void opposingAdministratorDeletesUseCanonicalRecipientsAndPreserveAccountOwnership()throws Exception{
        var sql=control();var root=loggedIn("ADMIN");String first=join(),second=join();int product=create(root,ownName());var pool=Executors.newFixedThreadPool(2);
        try{
            for(String id:List.of(first,second))assertEquals(200,request(root,"PUT","/api/admin/users/"+id,Map.of("role","ADMIN")).status());
            var one=login(first,password);var two=login(second,password);int a=review(one,product),b=review(two,product);
            sql.update("UPDATE pubburi.t_comment SET user_id=? WHERE id=?",first.toUpperCase(Locale.ROOT),a);
            sql.update("UPDATE pubburi.t_comment SET user_id=? WHERE id=?",second.toUpperCase(Locale.ROOT),b);
            var start=new CountDownLatch(1);var x=pool.submit(()->{start.await();return request(one,"DELETE","/api/comments/"+b,null).status();});var y=pool.submit(()->{start.await();return request(two,"DELETE","/api/comments/"+a,null).status();});start.countDown();
            assertEquals(200,x.get(15,TimeUnit.SECONDS));assertEquals(200,y.get(15,TimeUnit.SECONDS));
            assertEquals(1,page(one,first).path("total").intValue());assertEquals(1,page(two,second).path("total").intValue());
            assertEquals(first,stored(sql,first).get(0).get("user_id"));assertEquals(second,stored(sql,second).get(0).get("user_id"));
        }finally{pool.shutdownNow();assertTrue(pool.awaitTermination(3,TimeUnit.SECONDS));clean(sql,first,product,List.of());clean(sql,second,null,List.of());}
    }
    void self(boolean promote)throws Exception{
        var sql=control();var admin=loggedIn("ADMIN");String user=join();int product=create(admin,ownName());
        try{
            if(promote)assertEquals(200,request(admin,"PUT","/api/admin/users/"+user,Map.of("role","ADMIN")).status());
            var owner=login(user,password);int id=review(owner,product);String preserved=user.toUpperCase(Locale.ROOT);
            assertEquals(1,sql.update("UPDATE pubburi.t_comment SET user_id=? WHERE id=?",preserved,id));
            assertEquals(preserved,sql.queryForObject("SELECT user_id FROM pubburi.t_comment WHERE id=?",String.class,id));
            var removed=request(owner,"DELETE","/api/comments/"+id,null);var notifications=page(owner,user);
            assertAll(()->assertEquals(200,removed.status(),"The existing FK resolves to this canonical account"),
                ()->assertEquals(0,notifications.path("total").intValue(),"A self deletion never creates a moderation notification"));
        }finally{clean(sql,user,product,List.of());}
    }
}
