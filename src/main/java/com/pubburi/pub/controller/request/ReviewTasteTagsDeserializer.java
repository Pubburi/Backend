package com.pubburi.pub.controller.request;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.pubburi.pub.controller.api.CommentTasteTags;

/** Distinguishes an omitted optional field from an explicitly invalid null. */
public final class ReviewTasteTagsDeserializer extends JsonDeserializer<List<String>> {
    @Override public List<String> getAbsentValue(DeserializationContext context) { return null; }
    @Override public List<String> getNullValue(DeserializationContext context) throws JsonMappingException {
        return context.reportInputMismatch(List.class, "Review taste tags must be an array");
    }
    @Override public List<String> deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        if (!parser.hasToken(JsonToken.START_ARRAY)) return context.reportInputMismatch(List.class, "Review taste tags must be an array");
        var values = new ArrayList<String>();
        while (parser.nextToken() != JsonToken.END_ARRAY) {
            if (!parser.hasToken(JsonToken.VALUE_STRING) || values.size() == 20)
                return context.reportInputMismatch(List.class, "Invalid review taste tags");
            values.add(parser.getText());
        }
        return CommentTasteTags.normalize(values);
    }
}
