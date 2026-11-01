package com.pubburi.pub.security;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.exc.StreamConstraintsException;

class JsonDocumentConstraintTests {
	@Test
	void validDocumentAtTheConfiguredLimitRemainsReadable() throws Exception {
		consume("{\"a\":1234}");
	}

	@Test
	void finalBufferCannotBypassTheConfiguredDocumentLimit() {
		assertThatThrownBy(() -> consume("{\"a\":1,\"b\":2}"))
				.isInstanceOf(StreamConstraintsException.class);
	}

	private void consume(String json) throws Exception {
		JsonFactory factory = JsonFactory.builder().streamReadConstraints(
				StreamReadConstraints.builder().maxDocumentLength(10).build()).build();
		try (JsonParser parser = factory.createParser(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)))) {
			while (parser.nextToken() != null) { /* Consume through the final buffer. */ }
		}
	}
}
