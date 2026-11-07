package com.pubburi.rehearsal;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class NotebookInputTests {
    final ObjectMapper json=new ObjectMapper();
    ObjectNode create(){return json.createObjectNode().put("productId",1).put("experience","TRIED").putNull("rating").putNull("tasteTags").putNull("aromaTags").putNull("memo");}
    @Test void literalPrivateObservationsKeepNullEmptyFractionalZeroAndCodePointBoundaries()throws Exception{
        var node=create();node.put("rating",0);node.putArray("tasteTags").add("🥃".repeat(40)).add("  SWEET  ");node.putArray("aromaTags");node.put("memo","🥃".repeat(2000));
        var input=json.readValue(node.toString(),NotebookInput.Create.class);assertEquals(0,input.rating().intValueExact());
        assertEquals(List.of("🥃".repeat(40),"  SWEET  "),input.tasteTags());assertEquals(List.of(),input.aromaTags());assertEquals("🥃".repeat(2000),input.memo());
        node.put("rating",4.25).put("memo","");input=json.readValue(node.toString(),NotebookInput.Create.class);assertEquals("4.25",input.rating().toPlainString());assertEquals("",input.memo());
        node=create().put("experience","INTERESTED");assertNull(json.readValue(node.toString(),NotebookInput.Create.class).rating());
    }
    @Test void malformedUnknownCoercedOrInconsistentInputNeverBecomesAnObservation()throws Exception{
        var baseline=create();
        var invalid=new ArrayList<String>(List.of("null","[]","{}",baseline.toString()+" {}",baseline.toString().replace("\"experience\":\"TRIED\"","\"experience\":\"TRIED\",\"experience\":\"TRIED\"")));
        for(JsonNode product:List.of(json.getNodeFactory().numberNode(1.0),json.getNodeFactory().booleanNode(true),json.getNodeFactory().numberNode(0)))invalid.add(create().set("productId",product).toString());
        for(JsonNode score:List.of(json.getNodeFactory().numberNode(-0.1),json.getNodeFactory().numberNode(5.01),json.getNodeFactory().numberNode(1.234),json.getNodeFactory().textNode("4"),json.getNodeFactory().booleanNode(true)))invalid.add(create().set("rating",score).toString());
        invalid.add(create().put("memo","x".repeat(2001)).toString());invalid.add(create().put("memo","bad\0text").toString());invalid.add(create().put("memo","\ud800").toString());
        invalid.add(create().put("owner","other").toString());var missing=create();missing.remove("tasteTags");invalid.add(missing.toString());
        for(var tags:List.of(json.createArrayNode().add(" "),json.createArrayNode().add("same").add("same"),json.createArrayNode().add("🥃".repeat(41)),json.createArrayNode().addNull(),json.createArrayNode().add(json.createArrayNode().add("nested"))))invalid.add(create().set("tasteTags",tags).toString());
        var tooMany=json.createArrayNode();for(int i=0;i<21;i++)tooMany.add("tag"+i);invalid.add(create().set("aromaTags",tooMany).toString());
        invalid.add(create().put("experience","INTERESTED").put("rating",0).toString());var interest=create().put("experience","INTERESTED");interest.putArray("tasteTags");invalid.add(interest.toString());
        for(int i=0;i<invalid.size();i++){String body=invalid.get(i);assertThrows(Exception.class,()->json.readValue(body,NotebookInput.Create.class),"invalid case "+i);}
    }
    @Test void fullReplacementRequiresAnExactSafeRevisionAndNoProductRetargeting()throws Exception{
        var node=create();node.remove("productId");node.put("expectedVersion",0);assertEquals(0,json.readValue(node.toString(),NotebookInput.Change.class).expectedVersion());
        node.put("expectedVersion",NotebookInput.MAX_VERSION);assertEquals(NotebookInput.MAX_VERSION,json.readValue(node.toString(),NotebookInput.Change.class).expectedVersion());
        for(var version:List.of(json.getNodeFactory().numberNode(-1),json.getNodeFactory().numberNode(1.0),json.getNodeFactory().textNode("0"),json.getNodeFactory().numberNode(NotebookInput.MAX_VERSION+1))){node.set("expectedVersion",version);assertThrows(Exception.class,()->json.readValue(node.toString(),NotebookInput.Change.class));}
        node.put("expectedVersion",0).put("productId",2);assertThrows(Exception.class,()->json.readValue(node.toString(),NotebookInput.Change.class));
    }
}
