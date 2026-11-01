package com.pubburi.pub.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.Reader;
import java.io.StringReader;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.exc.StreamConstraintsException;
import com.fasterxml.jackson.core.util.JsonRecyclerPools;

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
	void freshTextBufferStopsBeforeAccumulatingTheEntireOversizedFieldName() {
		ChunkedReader reader = new ChunkedReader("{\"" + "a".repeat(2048) + "\":1}");
		JsonFactory factory = JsonFactory.builder().recyclerPool(JsonRecyclerPools.nonRecyclingPool()).streamReadConstraints(
				StreamReadConstraints.builder().maxNameLength(64).build()).build();
		assertThatThrownBy(() -> {
			try (JsonParser parser = factory.createParser(reader)) {
				while (parser.nextToken() != null) { /* Parse the bounded fixture. */ }
			}
		}).isInstanceOf(StreamConstraintsException.class);
		assertThat(reader.consumed).isLessThan(2048);
		System.out.println("reader-fixture-consumed-characters=" + reader.consumed);
	}

	@Test
	void defaultRecycledBufferEnforcesTheNameLimitWhenAccumulationWouldGrowAgain() throws Exception {
		// A new thread gives the real default ThreadLocal pool a known initial lifetime.
		// Within that lifetime we explicitly reproduce buffer reuse; do not clear it away.
		FutureTask<ReusedConsumption> task = new FutureTask<>(() -> {
			try (JsonParser parser = new JsonFactory().createParser(new StringReader("\"" + "x".repeat(60000) + "\""))) {
				parser.nextToken(); parser.getText();
			}
			JsonFactory smallLimit = JsonFactory.builder().streamReadConstraints(
					StreamReadConstraints.builder().maxNameLength(64).build()).build();
			int small = rejectedCharacters(smallLimit, 2048);
			JsonFactory defaults = new JsonFactory();
			assertThat(defaults.streamReadConstraints().getMaxNameLength()).isEqualTo(50000);
			return new ReusedConsumption(small, rejectedCharacters(defaults, 140000));
		});
		Thread worker = new Thread(task, "json-default-recycler-contract");
		worker.setDaemon(true);
		worker.start();
		ReusedConsumption consumed = task.get(5, TimeUnit.SECONDS);
		// An already allocated large segment may contain the whole small name.
		assertThat(consumed.small()).isEqualTo(2054);
		// The patch bounds new segment growth, rather than promising byte-exact stopping.
		assertThat(consumed.large()).isLessThan(140000);
		System.out.println("default-recycled-name-consumed-characters=" + consumed.small() + "/" + consumed.large());
	}

	private int rejectedCharacters(JsonFactory factory, int keyLength) {
		ChunkedReader reader = new ChunkedReader("{\"" + "a".repeat(keyLength) + "\":1}");
		assertThatThrownBy(() -> {
			try (JsonParser parser = factory.createParser(reader)) {
				while (parser.nextToken() != null) { /* Read until the configured name limit rejects the input. */ }
			}
		}).isInstanceOf(StreamConstraintsException.class);
		return reader.consumed;
	}

	private record ReusedConsumption(int small, int large) { }

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
