package com.pubburi.pub.controller.request;

import java.io.IOException;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;

/** Do not silently truncate a measured volume or coerce a string into a quantity. */
public class StrictIntegerDeserializer extends JsonDeserializer<Integer> {
	@Override
	public Integer deserialize(JsonParser parser, DeserializationContext context) throws IOException {
		if (!parser.hasToken(JsonToken.VALUE_NUMBER_INT)) {
			return (Integer) context.handleUnexpectedToken(Integer.class, parser);
		}
		return parser.getIntValue();
	}
}
