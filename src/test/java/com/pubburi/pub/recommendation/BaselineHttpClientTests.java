package com.pubburi.pub.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

class BaselineHttpClientTests {
	private static final String VALID = "{\"algorithm\":\"b0-id-ascending-v1\",\"items\":[{\"product_id\":2,\"reasons\":[\"available\",\"deterministic_baseline\"]}],\"eligible_count\":2,\"fallback_reason\":null}";

	@Test
	void boundedValidHttpRequestUsesOnlyIdsAndAvailabilityAndChecksTheCompleteContract() throws Exception {
		try (Server server = new Server()) {
			AtomicReference<String> request = new AtomicReference<>();
			server.handler.set(exchange -> {
				assertThat(exchange.getRequestURI().getPath()).isEqualTo("/v1/recommendations/baseline");
				assertThat(exchange.getRequestMethod()).isEqualTo("POST");
				request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
				reply(exchange, 200, "application/json", VALID);
			});
			assertThat(server.client().recommend(List.of(2, 5), 1)).contains(List.of(2));
			var payload = new ObjectMapper().readTree(request.get());
			assertThat(payload.size()).isEqualTo(3); assertThat(payload.path("constraints").size()).isZero();
			assertThat(payload.path("catalog").get(0).size()).isEqualTo(2);
		}
	}

	@Test
	void malformedOrUntrustedUpstreamBodiesNeverBecomeSuccessfulIds() throws Exception {
		try (Server server = new Server()) {
			AtomicReference<String> body = new AtomicReference<>();
			server.handler.set(exchange -> reply(exchange, 200, "application/json", body.get()));
			for (String invalid : List.of("not json", VALID + " {}", VALID.replace("b0-id-ascending-v1", "other-model"),
					VALID.replace("\"product_id\":2", "\"product_id\":99"), VALID.replace("\"product_id\":2", "\"product_id\":2.0"),
					VALID.replace("\"eligible_count\":2", "\"eligible_count\":1"), VALID.replace("available", "category_match"),
					VALID.replace("\"items\":[", "\"items\":[{\"product_id\":2,\"reasons\":[\"available\",\"deterministic_baseline\"]},"),
					VALID.replace("\"algorithm\":", "\"algorithm\":\"duplicate\",\"algorithm\":"), "x".repeat(32769))) {
				body.set(invalid); assertThat(server.client().recommend(List.of(2, 5), 1)).isEmpty();
			}
		}
	}

	@Test
	void errorsAndRedirectsAreRejectedWithoutFollowingOrTrustingTheirBodies() throws Exception {
		try (Server server = new Server()) {
			for (int status : List.of(302, 503)) {
				var requests = new java.util.concurrent.atomic.AtomicInteger();
				server.handler.set(exchange -> { requests.incrementAndGet(); exchange.getResponseHeaders().set("Location", "/must-not-follow"); reply(exchange, status, "application/json", VALID); });
				assertThat(server.client().recommend(List.of(2, 5), 1)).isEmpty();
				assertThat(requests.get()).isEqualTo(1);
			}
			server.handler.set(exchange -> reply(exchange, 200, "text/html", VALID));
			assertThat(server.client().recommend(List.of(2, 5), 1)).isEmpty();
		}
	}

	@Test
	void unknownLengthChunkedBodyStillCannotExceedTheByteCap() throws Exception {
		try (Server server = new Server()) {
			AtomicReference<String> body = new AtomicReference<>();
			server.handler.set(exchange -> {
				exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, 0);
				try (var output = exchange.getResponseBody()) { output.write(body.get().getBytes(StandardCharsets.UTF_8)); }
				catch (IOException cancelled) { /* The client is expected to cancel an oversized stream. */ }
			});
			String atLimit = VALID + " ".repeat(BaselineHttpClient.MAX_RESPONSE_BYTES - VALID.getBytes(StandardCharsets.UTF_8).length);
			body.set(atLimit);
			assertThat(server.client().recommend(List.of(2, 5), 1)).contains(List.of(2));
			String overLimit = atLimit + " ";
			assertThat(overLimit.getBytes(StandardCharsets.UTF_8)).hasSize(BaselineHttpClient.MAX_RESPONSE_BYTES + 1);
			assertThat(new ObjectMapper().readTree(overLimit)).isEqualTo(new ObjectMapper().readTree(VALID));
			body.set(overLimit);
			assertThat(server.client().recommend(List.of(2, 5), 1)).isEmpty();
		}
	}

	@Test
	void deadlineIncludesAnUnfinishedChunkedBodyNotJustHeaders() throws Exception {
		try (Server server = new Server()) {
			CountDownLatch release = new CountDownLatch(1); AtomicBoolean started = new AtomicBoolean();
			server.handler.set(exchange -> {
				exchange.getResponseHeaders().set("Content-Type", "application/json");
				exchange.sendResponseHeaders(200, 0);
				try (var output = exchange.getResponseBody()) {
					output.write("{\"algorithm\":".getBytes(StandardCharsets.UTF_8)); output.flush(); started.set(true);
					try { release.await(4, TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
				}
			});
			try {
				var client = new BaselineHttpClient(server.uri(), Duration.ofMillis(500), Duration.ofMillis(1000), new ObjectMapper());
				assertTimeoutPreemptively(Duration.ofSeconds(3), () -> assertThat(client.recommend(List.of(2, 5), 1)).isEmpty());
				assertThat(started).isTrue(); assertThat(release.getCount()).isEqualTo(1);
			} finally { release.countDown(); }
		}
	}

	@Test
	void bodyCapAndExplicitAbortCancelTheOwnedSubscription() {
		for (boolean oversized : List.of(true, false)) {
			var body = new BaselineHttpClient.LimitedBody(4); AtomicBoolean cancelled = new AtomicBoolean();
			body.onSubscribe(new Flow.Subscription() { public void request(long count) { } public void cancel() { cancelled.set(true); } });
			if (oversized) body.onNext(List.of(ByteBuffer.wrap(new byte[5]))); else body.abort();
			assertThat(cancelled).isTrue(); assertThat(body.getBody().toCompletableFuture().isCompletedExceptionally()).isTrue();
		}
	}

	@Test
	void refusedConnectionIsRecoverableAndCallerCannotChooseAnArbitraryApiPath() throws Exception {
		URI uri;
		try (Server server = new Server()) { uri = server.uri(); }
		assertThat(new BaselineHttpClient(uri, Duration.ofMillis(200), Duration.ofMillis(700), new ObjectMapper())
				.recommend(List.of(2, 5), 1)).isEmpty();
		org.assertj.core.api.Assertions.assertThatThrownBy(() -> new BaselineHttpClient(URI.create("http://localhost/private?x=1"),
				Duration.ofMillis(200), Duration.ofMillis(700), new ObjectMapper())).isInstanceOf(IllegalArgumentException.class);
	}

	private static void reply(HttpExchange exchange, int status, String type, String text) throws IOException {
		byte[] bytes = text.getBytes(StandardCharsets.UTF_8); exchange.getResponseHeaders().set("Content-Type", type);
		exchange.sendResponseHeaders(status, bytes.length);
		try (var output = exchange.getResponseBody()) { output.write(bytes); }
	}
	@FunctionalInterface private interface Handler { void handle(HttpExchange exchange) throws IOException; }
	private static final class Server implements AutoCloseable {
		final HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
		final java.util.concurrent.ExecutorService executor = Executors.newCachedThreadPool(r -> { Thread t = new Thread(r, "owned-baseline-fixture"); t.setDaemon(true); return t; });
		final AtomicReference<Handler> handler = new AtomicReference<>(exchange -> reply(exchange, 503, "application/json", "{}"));
		Server() throws IOException { server.createContext("/", exchange -> handler.get().handle(exchange)); server.setExecutor(executor); server.start(); }
		URI uri() { return URI.create("http://127.0.0.1:" + server.getAddress().getPort()); }
		BaselineHttpClient client() { return new BaselineHttpClient(uri(), Duration.ofMillis(500), Duration.ofMillis(1000), new ObjectMapper()); }
		@Override public void close() { server.stop(0); executor.shutdownNow(); }
	}
}
