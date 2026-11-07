package com.pubburi.rehearsal;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.pubburi.rehearsal.persistence.JpaNotebook.Experience;
import io.swagger.v3.oas.annotations.media.Schema;
import java.io.IOException;
import java.math.*;
import java.util.*;

/** Personal observations retain literal text, null/empty values and array order. */
public final class NotebookInput {
    public static final long MAX_VERSION=9007199254740991L;
    private static final ObjectReader EXACT_JSON=new ObjectMapper().reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private NotebookInput(){ }
    public record Content(Experience experience,BigDecimal rating,List<String> tasteTags,List<String> aromaTags,String memo){ }
    @JsonDeserialize(using=CreateDeserializer.class)
    @Schema(additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
    public record Create(int productId,Experience experience,BigDecimal rating,List<String> tasteTags,List<String> aromaTags,String memo){
        public Content content(){return new Content(experience,rating,tasteTags,aromaTags,memo);}
    }
    @JsonDeserialize(using=ChangeDeserializer.class)
    @Schema(additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
    public record Change(Experience experience,BigDecimal rating,List<String> tasteTags,List<String> aromaTags,String memo,
        @Schema(minimum="0",maximum="9007199254740991") long expectedVersion){
        public Content content(){return new Content(experience,rating,tasteTags,aromaTags,memo);}
    }
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("Invalid notebook input");}
    private static JsonNode root(JsonParser parser,String...fields)throws IOException{
        parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        JsonNode value=EXACT_JSON.readTree(parser);
        if(value==null || !value.isObject() || value.size()!=fields.length || Arrays.stream(fields).anyMatch(key->!value.has(key)) || parser.nextToken()!=null)throw invalid();
        return value;
    }
    private static String literal(JsonNode value,int limit,boolean nullable){
        if(value==null)throw invalid();if(nullable && value.isNull())return null;
        if(!value.isTextual())throw invalid();String text=value.textValue();
        if(text.indexOf('\0')>=0 || text.codePointCount(0,text.length())>limit
            || text.codePoints().anyMatch(point->point>=Character.MIN_SURROGATE && point<=Character.MAX_SURROGATE))throw invalid();
        return text;
    }
    private static List<String> tags(JsonNode node){
        if(node.isNull())return null;if(!node.isArray() || node.size()>20)throw invalid();
        var values=new ArrayList<String>();var seen=new HashSet<String>();
        for(var item:node){String text=literal(item,40,false);if(text.isBlank() || !seen.add(text))throw invalid();values.add(text);}
        return List.copyOf(values);
    }
    private static Content content(JsonNode node){
        String state=literal(node.get("experience"),10,false);Experience experience;
        try{experience=Experience.valueOf(state);}catch(IllegalArgumentException error){throw invalid();}
        JsonNode score=node.get("rating");BigDecimal rating=null;
        if(!score.isNull()){
            if(!score.isNumber())throw invalid();
            BigDecimal observed=score.decimalValue();
            if(observed.signum()<0 || observed.compareTo(BigDecimal.valueOf(5))>0 || observed.stripTrailingZeros().scale()>2)throw invalid();
            try{rating=observed.setScale(2,RoundingMode.UNNECESSARY);}catch(ArithmeticException error){throw invalid();}
        }
        var taste=tags(node.get("tasteTags"));var aroma=tags(node.get("aromaTags"));String memo=literal(node.get("memo"),2000,true);
        if(experience==Experience.INTERESTED && (rating!=null || taste!=null || aroma!=null))throw invalid();
        return new Content(experience,rating,taste,aroma,memo);
    }
    public static long version(JsonNode node){
        if(node==null || !node.isIntegralNumber() || !node.canConvertToLong() || node.longValue()<0 || node.longValue()>MAX_VERSION)throw invalid();return node.longValue();
    }
    public static class CreateDeserializer extends JsonDeserializer<Create>{
        @Override public Create getNullValue(DeserializationContext context)throws JsonMappingException{return context.reportInputMismatch(Create.class,"Notebook object is required");}
        @Override public Create deserialize(JsonParser p,DeserializationContext context)throws IOException{
            JsonNode root=root(p,"productId","experience","rating","tasteTags","aromaTags","memo");JsonNode id=root.get("productId");
            if(!id.isIntegralNumber() || !id.canConvertToInt() || id.intValue()<1)throw invalid();var c=content(root);
            return new Create(id.intValue(),c.experience(),c.rating(),c.tasteTags(),c.aromaTags(),c.memo());
        }
    }
    public static class ChangeDeserializer extends JsonDeserializer<Change>{
        @Override public Change getNullValue(DeserializationContext context)throws JsonMappingException{return context.reportInputMismatch(Change.class,"Notebook object is required");}
        @Override public Change deserialize(JsonParser p,DeserializationContext context)throws IOException{
            JsonNode root=root(p,"experience","rating","tasteTags","aromaTags","memo","expectedVersion");var c=content(root);
            return new Change(c.experience(),c.rating(),c.tasteTags(),c.aromaTags(),c.memo(),version(root.get("expectedVersion")));
        }
    }
}
