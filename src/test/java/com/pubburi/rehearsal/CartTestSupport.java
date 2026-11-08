package com.pubburi.rehearsal;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

abstract class CartTestSupport extends JpaAccountTestSupport {
    static final String PATH="/api/users/me/cart";
    Reply cart(HttpClient client,String actor,String method,String suffix,Object input)throws Exception{
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+PATH+suffix)).timeout(Duration.ofSeconds(12)).header("Accept","application/json");
        if(actor!=null)request.header(NotebookController.OWNER_HEADER,URLEncoder.encode(actor,StandardCharsets.UTF_8).replace("+","%20"));
        String body=input==null?null:input instanceof String?(String)input:json.writeValueAsString(input);if(body!=null)request.header("Content-Type","application/json");
        request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body));var response=client.send(request.build(),HttpResponse.BodyHandlers.ofString());
        assertEquals("private, no-store",response.headers().firstValue("Cache-Control").orElseThrow());assertTrue(response.headers().allValues("Vary").stream().anyMatch(value->value.contains("Cookie")));
        return new Reply(response.statusCode(),json.readTree(response.body()));
    }
    JsonNode get(HttpClient client,String owner)throws Exception{var reply=cart(client,owner,"GET","",null);assertEquals(200,reply.status());return reply.body().path("data");}
    long version(JsonNode cart){return cart.path("version").longValue();}
    JsonNode line(JsonNode cart,int product){for(var row:cart.path("items"))if(row.path("productId").intValue()==product)return row;throw new AssertionError("Owned cart line absent");}
    JsonNode put(HttpClient client,String owner,int product,int quantity,long version)throws Exception{var reply=cart(client,owner,"PUT","/items/"+product,Map.of("quantity",quantity,"expectedVersion",version));assertEquals(200,reply.status());return reply.body().path("data");}
    Map<String,Object> merge(UUID id,long version,List<Map<String,Integer>> items){return new HashMap<>(Map.of("operationId",id.toString(),"expectedVersion",version,"items",items));}
    Map<String,Object> checkout(UUID id,long version,List<Map<String,Integer>> items){var value=merge(id,version,items);value.put("orderType","TAKEOUT");value.put("orderTable",null);return value;}
    Map<String,Integer> item(int product,int quantity){return Map.of("productId",product,"quantity",quantity);}
    Map<String,Object> cartState(JdbcTemplate sql,String owner){return Map.of("header",sql.queryForList("SELECT * FROM pubburi.t_user_cart WHERE user_id=?",owner),"items",sql.queryForList("SELECT * FROM pubburi.t_user_cart_item WHERE user_id=? ORDER BY product_id",owner),"operations",sql.queryForList("SELECT * FROM pubburi.t_user_cart_operation WHERE user_id=? ORDER BY operation_id",owner));}
}
