package com.pubburi.rehearsal;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties="server.port=0")
@EnabledIfEnvironmentVariable(named="PUBBURI_JPA_INTEGRATION",matches="1")
class JpaNotificationOpenApiIntegrationTests extends NotificationTestSupport {
    @Test void notificationAndNotebookReferencesKeepTheirOwnFieldsEnumsAndObservedNulls()throws Exception{
        var sql=control();var admin=loggedIn("ADMIN");String user=join();int product=create(admin,ownName());
        try{
            var owner=login(user,password);int order=order(owner,product),review=review(owner,product);
            var header=Map.of(NotebookController.OWNER_HEADER,URLEncoder.encode(user,StandardCharsets.UTF_8).replace("+","%20"));
            var input=new HashMap<String,Object>();input.put("productId",product);input.put("experience","INTERESTED");for(String key:List.of("rating","tasteTags","aromaTags","memo"))input.put(key,null);
            var created=request(owner,"POST","/api/users/me/notebook",input,header);assertEquals(201,created.status());String note=created.body().at("/data/id").textValue();
            assertEquals(200,request(admin,"PATCH","/api/admin/orders/"+order+"/complete",null).status());assertEquals(200,request(admin,"DELETE","/api/comments/"+review,null).status());
            assertEquals(200,request(admin,"DELETE","/api/admin/products/"+product,null).status());
            var retained=request(owner,"GET","/api/users/me/notebook/"+note,null,header);assertEquals(200,retained.status());
            var notebook=retained.body().path("data");var notifications=page(owner,user);assertEquals(2,notifications.path("items").size());
            var response=request(owner,"GET","/v3/api-docs",null);assertEquals(200,response.status());var spec=response.body();
            java.nio.file.Files.writeString(java.nio.file.Path.of("target/notification-openapi-spec.json"),spec.toPrettyString());
            assertTrue(spec.path("openapi").textValue().startsWith("3.1."));
            var notificationPage=dataSchema(spec,PATH);assertEquals(Set.of("items","page","size","total","totalPages","hasNext","unreadCount"),keys(notificationPage.path("properties")));
            var notificationEntry=resolve(spec,notificationPage.at("/properties/items/items"));
            var notebookPage=dataSchema(spec,"/api/users/me/notebook");assertEquals(Set.of("items","page","size","total","totalPages","hasNext"),keys(notebookPage.path("properties")));
            var notebookEntry=resolve(spec,notebookPage.at("/properties/items/items"));
            var notificationFields=Set.of("id","type","createdAt","readAt","orderId","productId","productName","targetAvailable");
            var notebookFields=Set.of("id","productId","productName","currentProduct","experience","rating","tasteTags","aromaTags","memo","version","createdAt","updatedAt");
            assertEquals(notificationFields,keys(notificationEntry.path("properties")));assertEquals(notebookFields,keys(notebookEntry.path("properties")));assertEquals(notebookFields,keys(notebook));
            var notificationNulls=Set.of("readAt","orderId","productId","productName");var observedNulls=new HashSet<String>();var types=new HashSet<String>();
            for(var entry:notifications.path("items")){
                assertEquals(notificationFields,keys(entry));types.add(entry.path("type").textValue());
                for(String key:notificationFields)if(entry.path(key).isNull())observedNulls.add(key);
            }
            assertEquals(notificationNulls,observedNulls);assertEquals(Set.of("ORDER_COMPLETED","REVIEW_REMOVED"),types);
            assertEquals(types,strings(notificationEntry.at("/properties/type/enum")));
            assertTypes(notificationEntry,Map.of("id","string","type","string","createdAt","integer","readAt","integer","orderId","integer","productId","integer","productName","string","targetAvailable","boolean"),notificationNulls);
            var notebookNulls=Set.of("productId","currentProduct","rating","tasteTags","aromaTags","memo");
            for(String key:notebookNulls)assertTrue(notebook.path(key).isNull(),key);
            assertTypes(notebookEntry,Map.ofEntries(Map.entry("id","string"),Map.entry("productId","integer"),Map.entry("productName","string"),Map.entry("currentProduct","object"),Map.entry("experience","string"),Map.entry("rating","number"),Map.entry("tasteTags","array"),Map.entry("aromaTags","array"),Map.entry("memo","string"),Map.entry("version","integer"),Map.entry("createdAt","integer"),Map.entry("updatedAt","integer")),notebookNulls);
            assertEquals(Set.of("id","name","type","price","img","abv","orderCount"),keys(notebookEntry.at("/properties/currentProduct/properties")));
            assertEquals("INTERESTED",notebook.path("experience").textValue());
            assertEquals(Set.of("TRIED","INTERESTED"),strings(notebookEntry.at("/properties/experience/enum")));
        }finally{clean(sql,user,product,List.of());}
    }
    private JsonNode dataSchema(JsonNode spec,String path){
        var envelope=resolve(spec,spec.path("paths").path(path).at("/get/responses/200/content/application~1json/schema"));return resolve(spec,envelope.at("/properties/data"));
    }
    private JsonNode resolve(JsonNode spec,JsonNode node){
        var visited=new HashSet<String>();while(node.has("$ref")){String ref=node.path("$ref").textValue();assertTrue(ref.startsWith("#/components/schemas/"));assertTrue(visited.add(ref));node=spec.at(ref.substring(1));assertFalse(node.isMissingNode());}return node;
    }
    private Set<String> strings(JsonNode array){assertTrue(array.isArray());var result=new HashSet<String>();array.forEach(value->result.add(value.textValue()));return result;}
    private void assertTypes(JsonNode schema,Map<String,String> base,Set<String> nullable){
        schema.path("properties").fields().forEachRemaining(field->{
            var expected=new HashSet<String>();expected.add(base.get(field.getKey()));if(nullable.contains(field.getKey()))expected.add("null");
            var type=field.getValue().path("type");var actual=type.isArray()?strings(type):Set.of(type.textValue());
            assertEquals(expected,actual,field.getKey()+": "+field.getValue());
        });
    }
}
