package com.pubburi.rehearsal;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

abstract class NotificationTestSupport extends JpaAccountTestSupport {
    static final String PATH="/api/users/me/notifications";
    Reply notification(HttpClient client,String owner,String method,String suffix,Object body)throws Exception{
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+PATH+suffix)).timeout(Duration.ofSeconds(12)).header("Accept","application/json");
        if(owner!=null)builder.header(NotebookController.OWNER_HEADER,URLEncoder.encode(owner,StandardCharsets.UTF_8).replace("+","%20"));
        String text=body instanceof String?(String)body:body==null?null:json.writeValueAsString(body);
        if(text!=null)builder.header("Content-Type","application/json");builder.method(method,text==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(text));
        var response=client.send(builder.build(),HttpResponse.BodyHandlers.ofString());
        assertEquals("private, no-store",response.headers().firstValue("Cache-Control").orElseThrow());assertTrue(response.headers().allValues("Vary").stream().anyMatch(value->value.contains("Cookie")));
        return new Reply(response.statusCode(),json.readTree(response.body()));
    }
    JsonNode page(HttpClient client,String owner)throws Exception{var result=notification(client,owner,"GET","",null);assertEquals(200,result.status());return result.body().path("data");}
    int order(HttpClient owner,int product)throws Exception{var result=request(owner,"POST","/api/orders",Map.of("details",List.of(Map.of("productId",product,"quantity",1))));assertEquals(201,result.status());return result.body().at("/data/oId").intValue();}
    int review(HttpClient owner,int product)throws Exception{var result=request(owner,"POST","/api/comments",Map.of("productId",product,"rating",4.25,"comment","private authored opinion"));assertEquals(201,result.status());return result.body().at("/data/id").intValue();}
    List<Map<String,Object>> stored(JdbcTemplate sql,String owner){return sql.queryForList("SELECT * FROM pubburi.t_user_notification WHERE user_id=? ORDER BY id",owner);}
}
