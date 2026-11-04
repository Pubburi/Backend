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
    public static boolean equal(JsonNode a,JsonNode b){
        if(a==null || b==null)return a==b;
        if(a.isNumber() && b.isNumber())return a.decimalValue().compareTo(b.decimalValue())==0;
        if(a.isArray() && b.isArray()){if(a.size()!=b.size())return false;for(int i=0;i<a.size();i++)if(!equal(a.get(i),b.get(i)))return false;return true;}
        if(a.isObject() && b.isObject()){if(a.size()!=b.size())return false;var names=a.fieldNames();while(names.hasNext()){String name=names.next();if(!b.has(name)||!equal(a.get(name),b.get(name)))return false;}return true;}
        return a.equals(b);
    }
}
