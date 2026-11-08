package com.pubburi.rehearsal;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.swagger.v3.oas.annotations.media.Schema;
import java.io.IOException;
import java.util.*;

public final class NotificationInput {
    private NotificationInput(){ }
    @JsonDeserialize(using=ReadDeserializer.class)
    @Schema(additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
    public record Read(List<UUID> ids){ }
    static UUID id(String text){
        try{UUID id=UUID.fromString(text);if(!id.toString().equals(text))throw invalid();return id;}
        catch(IllegalArgumentException error){throw invalid();}
    }
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("Invalid notification request");}
    public static class ReadDeserializer extends JsonDeserializer<Read> {
        @Override public Read getNullValue(DeserializationContext c)throws JsonMappingException{return c.reportInputMismatch(Read.class,"Read object is required");}
        @Override public Read deserialize(JsonParser parser,DeserializationContext c)throws IOException{
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);JsonNode root=parser.getCodec().readTree(parser);
            if(root==null || !root.isObject() || root.size()!=1 || !root.has("ids") || parser.nextToken()!=null)throw invalid();
            var values=root.get("ids");if(!values.isArray() || values.isEmpty() || values.size()>100)throw invalid();
            var ids=new ArrayList<UUID>();var seen=new HashSet<UUID>();
            for(var value:values){if(!value.isTextual())throw invalid();UUID id=id(value.textValue());if(!seen.add(id))throw invalid();ids.add(id);}
            return new Read(List.copyOf(ids));
        }
    }
}
