package com.pubburi.pub.controller.request;

import java.io.IOException;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.util.AccessPattern;

/** Do not silently truncate a measured volume or coerce a string into a quantity. */
public class StrictIntegerDeserializer extends JsonDeserializer<Integer> {
	@Override
	public Integer deserialize(JsonParser parser, DeserializationContext context) throws IOException {
		if (!parser.hasToken(JsonToken.VALUE_NUMBER_INT)) {
			return (Integer) context.handleUnexpectedToken(Integer.class, parser);
		}
		return parser.getIntValue();
	}

	/** Keep Jackson's int defaults without changing nullable Integer inputs. */
	public static final class Primitive extends StrictIntegerDeserializer {
		@Override
		public AccessPattern getNullAccessPattern() {
			return AccessPattern.DYNAMIC;
		}

		@Override
		public Integer getNullValue(DeserializationContext context) throws JsonMappingException {
			if (context.isEnabled(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)) {
				return context.reportInputMismatch(int.class, "Cannot map null into primitive int");
			}
			return 0;
		}
	}
}
