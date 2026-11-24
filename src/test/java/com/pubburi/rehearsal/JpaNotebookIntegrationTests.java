package com.pubburi.rehearsal;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pubburi.pub.controller.auth.FeedbackOwner;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties="server.port=0")
@Import(JpaFavoriteIntegrationTests.Probes.class)
@EnabledIfEnvironmentVariable(named="PUBBURI_JPA_INTEGRATION",matches="1")
class JpaNotebookIntegrationTests extends JpaAccountTestSupport {
    static final String PATH="/api/users/me/notebook";
    @Autowired JpaFavoriteIntegrationTests.Gate gate;
    Reply note(HttpClient client,String actor,String method,String suffix,Object input)throws Exception{
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+PATH+suffix)).timeout(Duration.ofSeconds(8)).header("Accept","application/json");
        if(actor!=null)builder.header(NotebookController.OWNER_HEADER,URLEncoder.encode(actor,StandardCharsets.UTF_8).replace("+","%20"));
        String body=input==null?null:input instanceof String?(String)input:json.writeValueAsString(input);
        if(body!=null)builder.header("Content-Type","application/json");builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body));
        var reply=client.send(builder.build(),HttpResponse.BodyHandlers.ofString());
        assertEquals("private, no-store",reply.headers().firstValue("Cache-Control").orElseThrow());
        assertTrue(reply.headers().allValues("Vary").stream().anyMatch(value->value.contains("Cookie")));
        return new Reply(reply.statusCode(),json.readTree(reply.body()));
    }
    Map<String,Object> content(String memo){var value=new HashMap<String,Object>();value.put("experience","TRIED");value.put("rating",4.25);value.put("tasteTags",List.of("  Raw,comma\n🥃  "));value.put("aromaTags",List.of());value.put("memo",memo);return value;}
    JsonNode createNote(HttpClient owner,String user,int product,String memo)throws Exception{
        var body=content(memo);body.put("productId",product);var reply=note(owner,user,"POST","",body);assertEquals(201,reply.status());return reply.body().path("data");
    }
    @Test void editsKeepRestoredFutureTimestampsAndRecentNoteOrderMonotonic()throws Exception{
        var sql=control();var admin=loggedIn("ADMIN");String user=join();
        int firstProduct=create(admin,ownName());Integer secondProduct=null;
        try{
            secondProduct=create(admin,ownName());var owner=login(user,password);
            var first=createNote(owner,user,firstProduct,"  first\n원문 🥃  ");
            var second=createNote(owner,user,secondProduct,"  second\n보존 🌾  ");
            String firstId=first.path("id").textValue(),secondId=second.path("id").textValue();
            assertEquals(0,sql.queryForObject("SELECT count(*) FROM pubburi.t_user_notebook WHERE user_id=? AND updated_at<created_at",Integer.class,user));
            Instant start=Instant.now().truncatedTo(ChronoUnit.MICROS);
            var ordinary=content("  ordinary\n보존 🌾  ");ordinary.put("expectedVersion",second.path("version").longValue());
            var saved=note(owner,user,"PUT","/"+secondId,ordinary);assertEquals(200,saved.status());
            Instant end=Instant.now();
            Instant savedTime=sql.queryForObject("SELECT updated_at FROM pubburi.t_user_notebook WHERE user_id=? AND id=?::uuid",java.sql.Timestamp.class,user,secondId).toInstant();
            assertFalse(savedTime.isBefore(start));assertFalse(savedTime.isAfter(end));
            assertEquals(second.path("createdAt"),saved.body().at("/data/createdAt"));
            Instant future=Instant.now().plus(Duration.ofHours(1)).truncatedTo(ChronoUnit.MICROS);
            assertEquals(1,sql.update("UPDATE pubburi.t_user_notebook SET updated_at=? WHERE user_id=? AND id=?::uuid",java.sql.Timestamp.from(future),user,firstId));
            assertEquals(1,sql.update("UPDATE pubburi.t_user_notebook SET updated_at=? WHERE user_id=? AND id=?::uuid",java.sql.Timestamp.from(future.minus(Duration.ofMinutes(30))),user,secondId));
            String stored="SELECT row_to_json(n)::text FROM pubburi.t_user_notebook n WHERE user_id=? AND id=?::uuid";
            ObjectNode before=(ObjectNode)json.readTree(sql.queryForObject(stored,String.class,user,firstId));
            String other=sql.queryForObject(stored,String.class,user,secondId);
            var current=note(owner,user,"GET","/"+firstId,null).body().path("data");
            var account=accountState(sql,user);var productOne=state(sql,firstProduct);var productTwo=state(sql,secondProduct);var source=sourceIdentity(sql);
            long version=current.path("version").longValue();Instant previous=future;
            for(String memo:List.of("  changed\n원문 🥃  ","  changed\n원문 🥃  ")){
                var input=content(memo);input.put("expectedVersion",version);
                var reply=note(owner,user,"PUT","/"+firstId,input);assertEquals(200,reply.status());var changed=reply.body().path("data");
                Instant updated=sql.queryForObject("SELECT updated_at FROM pubburi.t_user_notebook WHERE user_id=? AND id=?::uuid",java.sql.Timestamp.class,user,firstId).toInstant();
                assertFalse(updated.isBefore(previous.plusNanos(1000)));
                assertTrue(changed.path("updatedAt").longValue()>=previous.toEpochMilli());assertEquals(++version,changed.path("version").longValue());
                ObjectNode expected=(ObjectNode)current.deepCopy();expected.put("memo",memo);expected.set("version",changed.get("version"));expected.set("updatedAt",changed.get("updatedAt"));
                assertEquals(expected,changed);assertEquals(changed,note(owner,user,"GET","/"+firstId,null).body().path("data"));
                var actual=json.readTree(sql.queryForObject(stored,String.class,user,firstId));
                assertEquals(version,actual.path("version").longValue());
                ObjectNode expectedRow=before.deepCopy();expectedRow.put("memo",memo);expectedRow.set("version",actual.get("version"));expectedRow.set("updated_at",actual.get("updated_at"));
                assertEquals(expectedRow,actual);
                var page=note(owner,user,"GET","?size=2",null);assertEquals(200,page.status());assertEquals(2,page.body().at("/data/total").intValue());
                assertEquals(List.of(firstId,secondId),List.of(page.body().at("/data/items/0/id").textValue(),page.body().at("/data/items/1/id").textValue()));
                previous=updated;current=changed;
            }
            assertEquals(other,sql.queryForObject(stored,String.class,user,secondId));
            assertEquals(account,accountState(sql,user));assertEquals(productOne,state(sql,firstProduct));assertEquals(productTwo,state(sql,secondProduct));
            assertEquals(source,sourceIdentity(sql));
        }finally{
            clean(sql,user,secondProduct,List.of());
            sql.update("DELETE FROM pubburi.t_product WHERE id=? AND name LIKE 'ci_jpa_write_%'",firstProduct);
        }
    }
    @Test void ownPrivateCrudRetainsDeletedProductNotesAndNeverCrossesAccountOrRejoinBoundaries()throws Exception{
        var sql=control();var admin=loggedIn("ADMIN");String user=join(),other=join();int product=create(admin,ownName());Integer second=null;
        try{
            var owner=login(user,password);var stranger=login(other,password);
            assertEquals(401,note(client(),user,"GET","",null).status());assertEquals(400,note(owner,null,"GET","",null).status());assertEquals(401,note(owner,other,"GET","",null).status());
            assertTrue(note(owner,user,"GET","/by-product/"+product,null).body().path("data").isNull());
            var saved=createNote(owner,user,product,"  private\n원문 🥃  ");String id=saved.path("id").textValue();String originalName=saved.path("productName").textValue();
            assertEquals(0,saved.path("version").longValue());assertTrue(saved.path("createdAt").isIntegralNumber());assertEquals("  Raw,comma\n🥃  ",saved.at("/tasteTags/0").textValue());assertTrue(saved.path("aromaTags").isEmpty());
            var duplicate=content("duplicate");duplicate.put("productId",product);assertEquals(409,note(owner,user,"POST","",duplicate).status());
            assertEquals(404,note(stranger,other,"GET","/"+id,null).status());assertEquals(404,note(admin,required("PUBBURI_JPA_ADMIN_ID"),"GET","/"+id,null).status());
            var empty=note(stranger,other,"GET","",null).body().path("data");assertEquals(0,empty.path("total").intValue());assertTrue(empty.path("items").isEmpty());
            assertEquals(1,note(owner,user,"GET","?experience=TRIED&size=1",null).body().at("/data/total").intValue());assertEquals(0,note(owner,user,"GET","?experience=INTERESTED",null).body().at("/data/total").intValue());
            var renamed=new HashMap<String,Object>(product(ownName(),3000));renamed.put("name","Changed current product");assertEquals(200,request(admin,"PUT","/api/admin/products/"+product,renamed).status());
            var current=note(owner,user,"GET","/"+id,null).body().path("data");assertEquals(originalName,current.path("productName").textValue());assertEquals("Changed current product",current.at("/currentProduct/name").textValue());assertEquals(0,current.path("version").longValue());
            assertEquals(200,request(admin,"DELETE","/api/admin/products/"+product,null).status());
            var retained=note(owner,user,"GET","/"+id,null).body().path("data");assertTrue(retained.path("productId").isNull());assertTrue(retained.path("currentProduct").isNull());assertEquals(originalName,retained.path("productName").textValue());assertEquals(saved.path("memo"),retained.path("memo"));assertEquals(0,retained.path("version").longValue());
            var interested=new HashMap<String,Object>();interested.put("experience","INTERESTED");for(String key:List.of("rating","tasteTags","aromaTags"))interested.put(key,null);interested.put("memo","");interested.put("expectedVersion",0);
            var changed=note(owner,user,"PUT","/"+id,interested);assertEquals(200,changed.status());assertEquals(1,changed.body().at("/data/version").longValue());assertEquals("",changed.body().at("/data/memo").textValue());
            assertTrue(note(owner,user,"GET","/by-product/"+product,null).body().path("data").isNull());assertEquals(409,note(owner,user,"DELETE","/"+id+"?expectedVersion=0",null).status());
            assertEquals(200,note(owner,user,"DELETE","/"+id+"?expectedVersion=1",null).status());assertEquals(404,note(owner,user,"GET","/"+id,null).status());
            second=create(admin,ownName());var lifetime=createNote(owner,user,second,"not inherited");
            assertEquals(200,request(admin,"DELETE","/api/admin/users/"+user,null).status());assertEquals(401,note(owner,user,"GET","",null).status());
            assertEquals(201,request(client(),"POST","/api/users",registration(user)).status());var replacement=login(user,password);
            assertEquals(0,note(replacement,user,"GET","",null).body().at("/data/total").intValue());assertEquals(404,note(replacement,user,"GET","/"+lifetime.path("id").textValue(),null).status());
            assertEquals(0,sql.queryForObject("SELECT count(*) FROM pubburi.t_user_notebook WHERE user_id=?",Integer.class,user));
        }finally{clean(sql,user,second,List.of());clean(sql,other,null,List.of());sql.update("DELETE FROM pubburi.t_product WHERE id=? AND name LIKE 'ci_jpa_write_%'",product);}
    }
    @Test void staleConcurrentMutationsAndRevokedEpochCannotOverwritePrivateObservations()throws Exception{
        var sql=control();var admin=loggedIn("ADMIN");String user=join();int product=create(admin,ownName());var executor=Executors.newFixedThreadPool(2);
        try{
            var one=login(user,password);var two=login(user,password);var created=createNote(one,user,product,"initial");String id=created.path("id").textValue();var start=new CountDownLatch(1);
            var a=content("one");a.put("expectedVersion",0);var b=content("two");b.put("expectedVersion",0);
            var first=executor.submit(()->{start.await();return note(one,user,"PUT","/"+id,a).status();});var second=executor.submit(()->{start.await();return note(two,user,"PUT","/"+id,b).status();});start.countDown();
            assertEquals(Set.of(200,409),Set.of(first.get(12,TimeUnit.SECONDS),second.get(12,TimeUnit.SECONDS)));
            var retained=note(one,user,"GET","/"+id,null).body().path("data");assertEquals(1,retained.path("version").longValue());
            try(var pause=gate.arm("write")){
                var late=content("must not commit");late.put("expectedVersion",1);var writing=executor.submit(()->note(one,user,"PUT","/"+id,late));assertTrue(pause.entered.await(4,TimeUnit.SECONDS));
                assertEquals(200,request(two,"PUT","/api/users/me/password",Map.of("currentPassword",password,"newPassword","Authored-notebook-new-pass")).status());pause.release.countDown();assertEquals(401,writing.get(10,TimeUnit.SECONDS).status());
            }
            var fresh=login(user,"Authored-notebook-new-pass");assertEquals(retained,note(fresh,user,"GET","/"+id,null).body().path("data"));
            for(String column:List.of("id","user_id","product_id","product_name","created_at"))assertThrows(DataAccessException.class,()->runtime.execute("UPDATE pubburi.t_user_notebook SET "+column+"="+column+" WHERE false"));
            assertThrows(DataAccessException.class,()->runtime.execute("TRUNCATE pubburi.t_user_notebook"));
            assertThrows(DataAccessException.class,()->runtime.update("INSERT INTO pubburi.t_user_notebook(id,user_id,product_name,experience,rating) VALUES (?::uuid,?,'authored','INTERESTED',1)",UUID.randomUUID().toString(),user));
        }finally{executor.shutdownNow();assertTrue(executor.awaitTermination(3,TimeUnit.SECONDS));clean(sql,user,product,List.of());}
    }
}
