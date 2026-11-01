package com.pubburi.pub.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.Reader;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.exc.StreamConstraintsException;

class JsonNameConstraintTests {
	@Test
	void normalCharacterInputStillParsesWithTheSameNameConstraint() throws Exception {
		JsonFactory factory = JsonFactory.builder().streamReadConstraints(
				StreamReadConstraints.builder().maxNameLength(64).build()).build();
		try (JsonParser parser = factory.createParser(new ChunkedReader("{\"fixture\":1}"))) {
			while (parser.nextToken() != null) { /* Normal field names remain readable. */ }
		}
	}

	@Test
	void characterInputStopsBeforeAccumulatingTheEntireOversizedFieldName() {
		ChunkedReader reader = new ChunkedReader("{\"" + "a".repeat(2048) + "\":1}");
		JsonFactory factory = JsonFactory.builder().streamReadConstraints(
				StreamReadConstraints.builder().maxNameLength(64).build()).build();
		assertThatThrownBy(() -> {
			try (JsonParser parser = factory.createParser(reader)) {
				while (parser.nextToken() != null) { /* Parse the bounded fixture. */ }
			}
		}).isInstanceOf(StreamConstraintsException.class);
		assertThat(reader.consumed).isLessThan(2048);
		System.out.println("reader-fixture-consumed-characters=" + reader.consumed);
	}

	private static class ChunkedReader extends Reader {
		private final String input;
		private int consumed;
		ChunkedReader(String input) { this.input = input; }
		@Override public int read(char[] buffer, int offset, int length) {
			if (consumed == input.length()) return -1;
			int count = Math.min(128, Math.min(length, input.length() - consumed));
			input.getChars(consumed, consumed + count, buffer, offset);
			consumed += count;
			return count;
		}
		@Override public void close() { }
	}
}
