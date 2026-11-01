package com.pubburi.pub.recommendation;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public final class BaselineHttpClient implements BaselineGateway {
	public static final int MAX_RESPONSE_BYTES = 32768;
	private final HttpClient client;
	private final URI endpoint;
	private final Duration deadline;
	private final ObjectMapper mapper;

	public BaselineHttpClient(URI baseUrl, Duration connectTimeout, Duration deadline, ObjectMapper mapper) {
		if (!("http".equals(baseUrl.getScheme()) || "https".equals(baseUrl.getScheme()))
				|| baseUrl.getHost() == null || baseUrl.getUserInfo() != null || baseUrl.getQuery() != null
				|| baseUrl.getFragment() != null || !(baseUrl.getPath().isEmpty() || "/".equals(baseUrl.getPath())))
			throw new IllegalArgumentException("Recommendation base URL must be an operator-configured HTTP origin");
		if (connectTimeout.isZero() || connectTimeout.isNegative() || deadline.isZero() || deadline.isNegative())
			throw new IllegalArgumentException("Recommendation timeouts must be positive");
		this.client = HttpClient.newBuilder().connectTimeout(connectTimeout).followRedirects(HttpClient.Redirect.NEVER).build();
		this.endpoint = baseUrl.resolve("/v1/recommendations/baseline");
		this.deadline = deadline;
		this.mapper = mapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
				.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
		this.mapper.getFactory().setStreamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(16)
				.maxStringLength(1024).maxNameLength(128).maxDocumentLength(MAX_RESPONSE_BYTES).build());
	}

	@Override
	public Optional<List<Integer>> recommend(List<Integer> candidates, int limit) {
		if (candidates.isEmpty() || candidates.size() > 1000 || limit < 1 || limit > 12
				|| candidates.stream().anyMatch(id -> id == null || id < 1)
				|| new HashSet<>(candidates).size() != candidates.size()
				|| !candidates.equals(candidates.stream().sorted().toList())) return Optional.empty();
		CompletableFuture<HttpResponse<byte[]>> exchange = null;
		AtomicReference<LimitedBody> active = new AtomicReference<>();
		AtomicBoolean cancelled = new AtomicBoolean();
		try {
			List<Map<String, Object>> catalog = candidates.stream()
					.map(id -> Map.<String, Object>of("product_id", id, "available", true)).toList();
			byte[] payload = mapper.writeValueAsBytes(Map.of("catalog", catalog, "constraints", Map.of(), "limit", limit));
			HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(deadline)
					.header("Content-Type", "application/json").header("Accept", "application/json")
					.POST(HttpRequest.BodyPublishers.ofByteArray(payload)).build();
			exchange = client.sendAsync(request, info -> {
				LimitedBody body = new LimitedBody(MAX_RESPONSE_BYTES);
				active.set(body);
				String type = info.headers().firstValue("Content-Type").orElse("").split(";", 2)[0].strip();
				boolean accepted = info.statusCode() == 200 && "application/json".equalsIgnoreCase(type);
				try { accepted &= info.headers().firstValueAsLong("Content-Length").orElse(0) <= MAX_RESPONSE_BYTES; }
				catch (NumberFormatException invalidLength) { accepted = false; }
				if (!accepted || cancelled.get()) body.abort();
				return body;
			});
			// get waits for LimitedBody.onComplete, including a slow/partial response body.
			byte[] content = exchange.get(deadline.toMillis(), TimeUnit.MILLISECONDS).body();
			return validate(mapper.readTree(content), candidates, limit);
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			return Optional.empty();
		} catch (java.io.IOException | ExecutionException | TimeoutException | IllegalArgumentException failure) {
			return Optional.empty();
		} finally {
			cancelled.set(true);
			if (exchange != null && !exchange.isDone()) exchange.cancel(true);
			LimitedBody body = active.get();
			if (body != null && !body.result.isDone()) body.abort();
		}
	}

	private Optional<List<Integer>> validate(JsonNode root, List<Integer> candidates, int limit) {
		if (!fields(root, Set.of("algorithm", "items", "eligible_count", "fallback_reason"))
				|| !root.path("algorithm").isTextual() || !"b0-id-ascending-v1".equals(root.path("algorithm").asText())
				|| !root.path("eligible_count").isIntegralNumber() || !root.path("eligible_count").canConvertToInt()
				|| root.path("eligible_count").intValue() != candidates.size() || !root.path("fallback_reason").isNull()
				|| !root.path("items").isArray()) return Optional.empty();
		List<Integer> ids = new ArrayList<>();
		for (JsonNode item : root.path("items")) {
			if (!fields(item, Set.of("product_id", "reasons")) || !item.path("product_id").isIntegralNumber()
					|| !item.path("product_id").canConvertToInt() || !item.path("reasons").isArray()
					|| item.path("reasons").size() != 2 || !item.path("reasons").get(0).isTextual()
					|| !item.path("reasons").get(1).isTextual()
					|| !"available".equals(item.path("reasons").get(0).asText())
					|| !"deterministic_baseline".equals(item.path("reasons").get(1).asText())) return Optional.empty();
			ids.add(item.path("product_id").intValue());
		}
		List<Integer> expected = candidates.subList(0, Math.min(limit, candidates.size()));
		return ids.equals(expected) ? Optional.of(List.copyOf(ids)) : Optional.empty();
	}

	private boolean fields(JsonNode node, Set<String> expected) {
		if (node == null || !node.isObject() || node.size() != expected.size()) return false;
		for (String field : expected) if (!node.has(field)) return false;
		return true;
	}

	static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
		private final int limit;
		private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		private final CompletableFuture<byte[]> result = new CompletableFuture<>();
		private volatile Flow.Subscription subscription;
		LimitedBody(int limit) { this.limit = limit; }
		@Override public CompletionStage<byte[]> getBody() { return result; }
		@Override public void onSubscribe(Flow.Subscription subscription) {
			this.subscription = subscription;
			if (result.isDone()) subscription.cancel(); else subscription.request(1);
		}
		@Override public void onNext(List<ByteBuffer> buffers) {
			if (result.isDone()) return;
			for (ByteBuffer buffer : buffers) {
				if ((long) bytes.size() + buffer.remaining() > limit) { abort(); return; }
				byte[] chunk = new byte[buffer.remaining()]; buffer.get(chunk); bytes.writeBytes(chunk);
			}
			if (!result.isDone()) subscription.request(1);
		}
		@Override public void onError(Throwable error) { result.completeExceptionally(new IllegalStateException("Recommendation transport failed")); }
		@Override public void onComplete() { result.complete(bytes.toByteArray()); }
		void abort() {
			result.completeExceptionally(new IllegalStateException("Recommendation response rejected"));
			Flow.Subscription current = subscription;
			if (current != null) current.cancel();
		}
	}
}
