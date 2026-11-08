package com.pubburi.pub.recommendation;

import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;

/** Exact consumer facts, not a scorer or a catalog authority loader. */
public final class ContentWire {
    private ContentWire() { }
    public static void require(boolean value){if(!value)throw new IllegalArgumentException("Invalid content recommendation contract");}
    public static boolean fields(JsonNode value,String... names){return value!=null && value.isObject() && value.size()==names.length && Arrays.stream(names).allMatch(value::has);}
    public static boolean text(JsonNode node,String value){return node!=null && node.isTextual() && value.equals(node.textValue());}
    public static int integer(JsonNode node,int min,int max){require(node!=null && node.isIntegralNumber() && node.canConvertToInt() && node.intValue()>=min && node.intValue()<=max);return node.intValue();}
    static Set<String> tokens(JsonNode product) {
        var values = new HashSet<String>();
        if (!product.path("category").isNull()) values.add("category:" + product.path("category").textValue());
        for (String kind : List.of("taste", "aroma")) {
            var tags = product.path(kind + "_tags");
            if (tags.isArray()) tags.forEach(value -> values.add(kind + ":" + value.textValue()));
        }
        return values;
    }
    // Python's provider sorts literal features by Unicode code point, not UTF-16 code unit.
    static int compareCodePoints(String left,String right){
        int i=0,j=0;
        while(i<left.length() && j<right.length()){
            int a=left.codePointAt(i),b=right.codePointAt(j);if(a!=b)return Integer.compare(a,b);
            i+=Character.charCount(a);j+=Character.charCount(b);
        }
        return Integer.compare(left.length()-i,right.length()-j);
    }
    public static boolean equal(JsonNode a,JsonNode b){
        if(a==null || b==null)return a==b;
        if(a.isNumber() && b.isNumber())return a.decimalValue().compareTo(b.decimalValue())==0;
        if(a.isArray() && b.isArray()){if(a.size()!=b.size())return false;for(int i=0;i<a.size();i++)if(!equal(a.get(i),b.get(i)))return false;return true;}
        if(a.isObject() && b.isObject()){if(a.size()!=b.size())return false;var names=a.fieldNames();while(names.hasNext()){String name=names.next();if(!b.has(name)||!equal(a.get(name),b.get(name)))return false;}return true;}
        return a.equals(b);
    }
}
