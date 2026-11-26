package com.pubburi.rehearsal;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class CartInputTests {
    final ObjectMapper json=new ObjectMapper();
    static final String ID="00000000-0000-4000-8000-000000000001";
    ObjectNode merge(){var n=json.createObjectNode().put("operationId",ID).put("expectedVersion",0);n.putArray("items").addObject().put("productId",2).put("quantity",1);return n;}
    @Test void onlyIntentIsBoundAcrossRevisionAndArrayOrderWhileLiteralOrderFieldsStayDistinct()throws Exception{
        var node=merge();node.withArray("items").addObject().put("productId",1).put("quantity",99);
        var one=json.readValue(node.toString(),CartInput.Merge.class);assertEquals(List.of(1,2),one.items().stream().map(CartInput.Item::productId).toList());
        node.put("expectedVersion",9);var two=json.readValue(node.toString(),CartInput.Merge.class);
        assertEquals(CartInput.intent(one.items(),null,null),CartInput.intent(two.items(),null,null));
        assertEquals(CartInput.intent(one.items(),null,null),CartInput.intent(List.of(one.items().get(1),one.items().get(0)),null,null));
        assertNotEquals(CartInput.intent(one.items(),null,null),CartInput.intent(one.items(),"TAKEOUT",null));
        assertNotEquals(CartInput.intent(one.items(),"TAKEOUT",null),CartInput.intent(one.items(),"TAKEOUT",""));
        node.put("orderType","TAKEOUT").put("orderTable","🥃".repeat(20));var checkout=json.readValue(node.toString(),CartInput.Checkout.class);assertEquals("🥃".repeat(20),checkout.orderTable());
        node.put("orderTable","  raw\n ");assertEquals("  raw\n ",json.readValue(node.toString(),CartInput.Checkout.class).orderTable());
        var resolve=node.deepCopy();resolve.remove(List.of("operationId","expectedVersion"));resolve.put("kind","CHECKOUT");var resolved=json.readValue(resolve.toString(),CartInput.Resolve.class);assertEquals(CartInput.intent(one.items(),"TAKEOUT","  raw\n "),CartInput.intent(resolved.items(),resolved.orderType(),resolved.orderTable()));
        resolve.put("kind","MERGE").putNull("orderType").putNull("orderTable");assertEquals(CartInput.intent(one.items(),null,null),CartInput.intent(json.readValue(resolve.toString(),CartInput.Resolve.class).items(),null,null));
    }
    @Test void malformedCoercedDuplicateOversizedAndAmbiguousIntentIsRejected()throws Exception{
        var invalid=new ArrayList<String>(List.of("null","[]","{}",merge()+" {}",merge().toString().replace("\"expectedVersion\":0","\"expectedVersion\":0,\"expectedVersion\":0")));
        for(var value:List.of(json.getNodeFactory().numberNode(1.0),json.getNodeFactory().numberNode(0),json.getNodeFactory().numberNode(100),json.getNodeFactory().textNode("1"),json.getNodeFactory().booleanNode(true))){var n=merge();((ObjectNode)n.withArray("items").get(0)).set("quantity",value);invalid.add(n.toString());}
        var duplicate=merge();duplicate.withArray("items").addObject().put("productId",2).put("quantity",1);invalid.add(duplicate.toString());
        var empty=merge();empty.putArray("items");invalid.add(empty.toString());var large=merge();var array=large.putArray("items");for(int i=1;i<=101;i++)array.addObject().put("productId",i).put("quantity",1);invalid.add(large.toString());
        invalid.add(merge().put("owner","other").toString());invalid.add(merge().put("operationId","00000000-0-4000-8000-1").toString());invalid.add(merge().put("expectedVersion",NotebookInput.MAX_VERSION+1).toString());invalid.add(merge().put("expectedVersion","0").toString());
        for(String input:invalid)assertThrows(Exception.class,()->json.readValue(input,CartInput.Merge.class));
        var checkout=merge().put("orderType","TAKEOUT").putNull("orderTable");
        for(String table:List.of("🥃".repeat(21),"bad\0text","\ud800")){checkout.put("orderTable",table);assertThrows(Exception.class,()->json.readValue(checkout.toString(),CartInput.Checkout.class));}
        checkout.putNull("orderTable").put("orderType","OTHER");assertThrows(Exception.class,()->json.readValue(checkout.toString(),CartInput.Checkout.class));
        for(String input:List.of("null","{\"quantity\":0,\"expectedVersion\":0}","{\"quantity\":1,\"expectedVersion\":1.0}","{\"quantity\":1,\"expectedVersion\":0,\"productId\":1}"))assertThrows(Exception.class,()->json.readValue(input,CartInput.Quantity.class));
        var resolve=merge();resolve.remove(List.of("operationId","expectedVersion"));resolve.put("kind","MERGE").putNull("orderType").putNull("orderTable");
        for(var wrong:List.of(resolve.deepCopy().put("expectedVersion",0),resolve.deepCopy().put("orderType","TAKEOUT"),resolve.deepCopy().put("orderTable","raw"),resolve.deepCopy().put("kind","CHECKOUT"),resolve.deepCopy().put("kind","OTHER")))assertThrows(Exception.class,()->json.readValue(wrong.toString(),CartInput.Resolve.class));
    }

    @Test void confirmedTotalIsOptionalStrictAndBoundToCheckoutResolution() throws Exception {
        var checkout = merge().put("orderType", "TAKEOUT").putNull("orderTable");
        var legacy = json.readValue(checkout.toString(), CartInput.Checkout.class);
        assertNull(legacy.expectedTotalPrice());
        var resolve = checkout.deepCopy();
        resolve.remove(List.of("operationId", "expectedVersion"));
        resolve.put("kind", "CHECKOUT");
        var legacyResolve = json.readValue(resolve.toString(), CartInput.Resolve.class);
        assertNull(legacyResolve.expectedTotalPrice());
        String legacyHash = "9529a64aa9936fc87d311bb210a4a6ac73f4e9bc9422f638a18328f45a4454e4";
        assertEquals(legacyHash, CartInput.intent(legacy.items(), legacy.orderType(), legacy.orderTable(), legacy.expectedTotalPrice()));
        assertEquals(legacyHash, CartInput.intent(legacyResolve.items(), legacyResolve.orderType(), legacyResolve.orderTable(), legacyResolve.expectedTotalPrice()));

        var quoted = json.readValue(checkout.put("expectedTotalPrice", 0).toString(), CartInput.Checkout.class);
        assertEquals(0, quoted.expectedTotalPrice());
        var quotedResolve = json.readValue(resolve.put("expectedTotalPrice", 0).toString(), CartInput.Resolve.class);
        String quotedHash = CartInput.intent(quoted.items(), quoted.orderType(), quoted.orderTable(), quoted.expectedTotalPrice());
        assertEquals(quotedHash, CartInput.intent(quotedResolve.items(), quotedResolve.orderType(), quotedResolve.orderTable(), quotedResolve.expectedTotalPrice()));
        assertNotEquals(legacyHash, quotedHash);
        assertNotEquals(quotedHash, CartInput.intent(quoted.items(), quoted.orderType(), quoted.orderTable(), 1));
        assertEquals(Integer.MAX_VALUE, json.readValue(checkout.put("expectedTotalPrice", Integer.MAX_VALUE).toString(),
                CartInput.Checkout.class).expectedTotalPrice());

        for (var invalid : List.of(NullNode.getInstance(), BooleanNode.TRUE, IntNode.valueOf(-1),
                LongNode.valueOf((long) Integer.MAX_VALUE + 1), DoubleNode.valueOf(1.0), TextNode.valueOf("1"))) {
            checkout.set("expectedTotalPrice", invalid);
            resolve.set("expectedTotalPrice", invalid);
            assertThrows(Exception.class, () -> json.readValue(checkout.toString(), CartInput.Checkout.class));
            assertThrows(Exception.class, () -> json.readValue(resolve.toString(), CartInput.Resolve.class));
        }
        String duplicate = checkout.put("expectedTotalPrice", 0).toString()
                .replace("\"expectedTotalPrice\":0", "\"expectedTotalPrice\":0,\"expectedTotalPrice\":1");
        assertThrows(Exception.class, () -> json.readValue(duplicate, CartInput.Checkout.class));
        resolve.put("kind", "MERGE").putNull("orderType").put("expectedTotalPrice", 0);
        assertThrows(Exception.class, () -> json.readValue(resolve.toString(), CartInput.Resolve.class));
        assertThrows(Exception.class, () -> json.readValue(merge().put("expectedTotalPrice", 0).toString(), CartInput.Merge.class));
    }
}
