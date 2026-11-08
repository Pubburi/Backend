package com.pubburi.rehearsal;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.swagger.v3.oas.annotations.media.Schema;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

public final class CartInput {
    public static final int MAX_ITEMS=100,MAX_QUANTITY=99;
    private CartInput(){ }
    @Schema(name="CartInputItem")
    public record Item(int productId,int quantity){ }
    @JsonDeserialize(using=QuantityDeserializer.class) @Schema(additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
    public record Quantity(int quantity,long expectedVersion){ }
    @JsonDeserialize(using=MergeDeserializer.class) @Schema(additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
    public record Merge(UUID operationId,long expectedVersion,List<Item> items){ }
    @JsonDeserialize(using=CheckoutDeserializer.class) @Schema(additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
    public record Checkout(UUID operationId,long expectedVersion,List<Item> items,String orderType,String orderTable){ }
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("Invalid cart input");}
    private static JsonNode root(JsonParser parser,String...keys)throws IOException{
        parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);JsonNode node=parser.getCodec().readTree(parser);
        if(node==null || !node.isObject() || node.size()!=keys.length || Arrays.stream(keys).anyMatch(key->!node.has(key)) || parser.nextToken()!=null)throw invalid();return node;
    }
    private static int integer(JsonNode node,int max){if(!node.isIntegralNumber() || !node.canConvertToInt() || node.intValue()<1 || node.intValue()>max)throw invalid();return node.intValue();}
    private static UUID operation(JsonNode node){
        if(!node.isTextual())throw invalid();try{UUID value=UUID.fromString(node.textValue());if(!value.toString().equals(node.textValue()))throw invalid();return value;}catch(IllegalArgumentException error){throw invalid();}
    }
    private static List<Item> items(JsonNode node){
        if(!node.isArray() || node.isEmpty() || node.size()>MAX_ITEMS)throw invalid();var ids=new HashSet<Integer>();var items=new ArrayList<Item>();
        for(var value:node){
            if(!value.isObject() || value.size()!=2 || !value.has("productId") || !value.has("quantity"))throw invalid();
            int id=integer(value.get("productId"),Integer.MAX_VALUE);if(!ids.add(id))throw invalid();items.add(new Item(id,integer(value.get("quantity"),MAX_QUANTITY)));
        }
        items.sort(Comparator.comparingInt(Item::productId));return List.copyOf(items);
    }
    private static String table(JsonNode node){
        if(node.isNull())return null;if(!node.isTextual())throw invalid();String text=node.textValue();
        if(text.codePointCount(0,text.length())>20 || text.indexOf('\0')>=0 || text.codePoints().anyMatch(p->p>=Character.MIN_SURROGATE && p<=Character.MAX_SURROGATE))throw invalid();return text;
    }
    public static String intent(List<Item> items,String orderType,String orderTable){
        // Length-delimited literal table and sorted unique integer pairs are independent of JSON spelling/revision.
        var value=new StringBuilder(orderType==null?"MERGE":"CHECKOUT:"+orderType).append('\0');
        if(orderTable==null)value.append('N');else value.append('S').append(orderTable.getBytes(StandardCharsets.UTF_8).length).append(':').append(orderTable);
        value.append('\0');for(var item:items.stream().sorted(Comparator.comparingInt(Item::productId)).toList())value.append(item.productId()).append(':').append(item.quantity()).append(';');
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toString().getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException error){throw new IllegalStateException(error);}
    }
    public static class QuantityDeserializer extends JsonDeserializer<Quantity>{
        @Override public Quantity getNullValue(DeserializationContext c)throws JsonMappingException{return c.reportInputMismatch(Quantity.class,"Cart object is required");}
        @Override public Quantity deserialize(JsonParser p,DeserializationContext c)throws IOException{var n=root(p,"quantity","expectedVersion");return new Quantity(integer(n.get("quantity"),MAX_QUANTITY),NotebookInput.version(n.get("expectedVersion")));}
    }
    public static class MergeDeserializer extends JsonDeserializer<Merge>{
        @Override public Merge getNullValue(DeserializationContext c)throws JsonMappingException{return c.reportInputMismatch(Merge.class,"Cart object is required");}
        @Override public Merge deserialize(JsonParser p,DeserializationContext c)throws IOException{var n=root(p,"operationId","expectedVersion","items");return new Merge(operation(n.get("operationId")),NotebookInput.version(n.get("expectedVersion")),items(n.get("items")));}
    }
    public static class CheckoutDeserializer extends JsonDeserializer<Checkout>{
        @Override public Checkout getNullValue(DeserializationContext c)throws JsonMappingException{return c.reportInputMismatch(Checkout.class,"Cart object is required");}
        @Override public Checkout deserialize(JsonParser p,DeserializationContext c)throws IOException{
            var n=root(p,"operationId","expectedVersion","items","orderType","orderTable");var type=n.get("orderType");
            if(!type.isTextual() || !Set.of("ONLINE","OFFLINE","TAKEOUT").contains(type.textValue()))throw invalid();
            return new Checkout(operation(n.get("operationId")),NotebookInput.version(n.get("expectedVersion")),items(n.get("items")),type.textValue(),table(n.get("orderTable")));
        }
    }
}
