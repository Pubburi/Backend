package com.pubburi.pub.controller.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Set;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;
import org.springframework.test.context.web.ServletTestExecutionListener;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@SpringBootTest(properties = "spring.sql.init.mode=never")
@AutoConfigureMockMvc
@Import(RuntimeDocumentationTests.NoDatabase.class)
// No Mockito mocks: do not attach an instrumentation agent merely to reset beans.
@TestExecutionListeners(listeners = {ServletTestExecutionListener.class, DependencyInjectionTestExecutionListener.class},
		mergeMode = TestExecutionListeners.MergeMode.REPLACE_DEFAULTS)
class RuntimeDocumentationTests {
	@Autowired private MockMvc mvc;
	@Autowired private ObjectMapper mapper;

	@Test
	void applicationMvcMappingsGenerateFeaturesAndServeSwaggerWithoutDatabaseQueries() throws Exception {
		String body = mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		JsonNode document = mapper.readTree(body);
		JsonNode paths = document.path("paths");
		assertThat(paths.path("/api/products/{id}/features").has("get")).isTrue();
		assertThat(paths.path("/api/admin/products/{id}/features").has("put")).isTrue();
		JsonNode responseContent = paths.path("/api/products/{id}/features").path("get")
				.path("responses").path("200").path("content");
		assertThat(responseContent.has("application/json")).isTrue();
		JsonNode envelope = resolve(document, responseContent.path("application/json").path("schema"));
		JsonNode features = resolve(document, envelope.path("properties").path("data"));
		Set<String> expected = Set.of("volumeMl", "description", "tasteTags", "aromaTags", "sourceName", "sourceUrl", "sourceNote");
		Set<String> responseFields = fieldNames(features.path("properties"));
		assertThat(responseFields).containsAll(expected).contains("productId").hasSize(8);
		for (String name : expected) {
			JsonNode schema = resolve(document, features.path("properties").path(name));
			boolean nullable = schema.path("nullable").asBoolean(false);
			if (schema.path("type").isArray()) {
				for (JsonNode type : schema.path("type")) nullable |= "null".equals(type.asText());
			}
			assertThat(nullable).as("nullable %s: %s", name, schema).isTrue();
		}
		JsonNode requestContent = paths.path("/api/admin/products/{id}/features").path("put")
				.path("requestBody").path("content");
		assertThat(requestContent.has("application/json")).isTrue();
		JsonNode request = resolve(document, requestContent.path("application/json").path("schema"));
		assertThat(fieldNames(request.path("properties"))).isEqualTo(expected);
		mvc.perform(get("/docs")).andExpect(status().is3xxRedirection());
		String ui = mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		assertThat(ui).contains("swagger-ui");
	}

	private JsonNode resolve(JsonNode document, JsonNode schema) {
		while (schema.has("$ref")) {
			schema = document.at(schema.path("$ref").asText().substring(1));
		}
		return schema;
	}

	@Test
	void productSearchDocumentKeepsUnitsRepeatedTagsAndExistingPageResponse() throws Exception {
		JsonNode document = mapper.readTree(mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString());
		JsonNode operation = document.path("paths").path("/api/products").path("get");
		var parameters = new java.util.HashMap<String, JsonNode>();
		operation.path("parameters").forEach(parameter -> parameters.put(parameter.path("name").asText(), parameter));
		assertThat(parameters.keySet()).contains("type", "q", "sort", "page", "size", "minPriceKrw", "maxPriceKrw", "minAbvPercent", "maxAbvPercent", "tasteTag", "aromaTag");
		assertThat(parameters.get("page").path("description").asText()).contains("2147483647 OFFSET", "400 BAD_REQUEST");
		for (String tag : Set.of("tasteTag", "aromaTag")) {
			JsonNode parameter = parameters.get(tag);
			assertThat(parameter.path("style").asText()).isEqualTo("form"); assertThat(parameter.path("explode").booleanValue()).isTrue();
			assertThat(parameter.path("schema").path("type").asText()).isEqualTo("array");
			assertThat(parameter.path("schema").path("maxItems").intValue()).isEqualTo(20);
		}
		assertThat(parameters.get("minPriceKrw").path("schema").path("type").asText()).isEqualTo("integer");
		assertThat(parameters.get("maxAbvPercent").path("schema").path("maximum").decimalValue()).isEqualByComparingTo("100");
		JsonNode page = resolve(document, resolve(document, operation.path("responses").path("200")
				.path("content").path("application/json").path("schema")).path("properties").path("data"));
		assertThat(fieldNames(page.path("properties"))).isEqualTo(Set.of("items", "page", "size", "total", "totalPages", "hasNext"));
		assertThat(fieldNames(resolve(document, page.path("properties").path("items").path("items")).path("properties"))).hasSize(7);
	}

	@Test
	void privatePreferenceDocumentSeparatesSoftTargetsFromHardBoundsAndUnknowns() throws Exception {
		JsonNode document = mapper.readTree(mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString());
		JsonNode paths = document.path("paths");
		JsonNode operations = paths.path("/api/users/me/preferences");
		assertThat(operations.has("get") && operations.has("put")).isTrue();
		assertThat(paths.has("/api/users/{id}/preferences")).isFalse();
		JsonNode envelope = resolve(document, operations.path("get").path("responses").path("200")
				.path("content").path("application/json").path("schema"));
		JsonNode response = resolve(document, envelope.path("properties").path("data"));
		assertThat(fieldNames(response.path("properties"))).isEqualTo(Set.of("surveyStatus", "softPreferences", "hardConstraints"));
		for (String group : Set.of("softPreferences", "hardConstraints")) {
			JsonNode values = resolve(document, response.path("properties").path(group)).path("properties");
			assertThat(values.size()).isEqualTo(4);
			values.fields().forEachRemaining(field -> assertThat(allowsNull(resolve(document, field.getValue())))
					.as("nullable private preference %s", field.getKey()).isTrue());
		}
		JsonNode input = resolve(document, operations.path("put").path("requestBody").path("content")
				.path("application/json").path("schema"));
		assertThat(fieldNames(input.path("properties"))).isEqualTo(Set.of("surveyStatus", "softPreferences", "hardConstraints"));
		assertThat(allowsNull(resolve(document, input.path("properties").path("softPreferences")))).isTrue();
		assertThat(allowsNull(resolve(document, input.path("properties").path("hardConstraints")))).isTrue();
	}

	private boolean allowsNull(JsonNode schema) {
		if (schema.path("nullable").asBoolean(false)) return true;
		for (JsonNode type : schema.path("type")) if ("null".equals(type.asText())) return true;
		return false;
	}

	@Test
	void favoritesDocumentHasOnlySelfPathsAndTheCurrentProductPageContract() throws Exception {
		JsonNode document = mapper.readTree(mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString());
		JsonNode paths = document.path("paths");
		assertThat(paths.has("/api/users/{id}/favorites")).isFalse();
		JsonNode list = paths.path("/api/users/me/favorites");
		JsonNode member = paths.path("/api/users/me/favorites/{productId}");
		assertThat(list.has("get")).isTrue();
		assertThat(member.has("get") && member.has("put") && member.has("delete")).isTrue();
		assertThat(member.has("post")).isFalse();
		JsonNode envelope = resolve(document, list.path("get").path("responses").path("200").path("content")
				.path("application/json").path("schema"));
		JsonNode page = resolve(document, envelope.path("properties").path("data"));
		assertThat(fieldNames(page.path("properties"))).isEqualTo(Set.of("items", "page", "size", "total", "totalPages", "hasNext"));
		JsonNode product = resolve(document, page.path("properties").path("items").path("items"));
		assertThat(fieldNames(product.path("properties"))).isEqualTo(Set.of("id", "name", "type", "price", "img", "abv", "orderCount"));
		for (String method : Set.of("get", "put", "delete")) {
			JsonNode operation = member.path(method);
			assertThat(operation.has("requestBody")).isFalse();
			JsonNode mutation = resolve(document, operation.path("responses").path("200").path("content")
					.path("application/json").path("schema"));
			assertThat(fieldNames(resolve(document, mutation.path("properties").path("data")).path("properties")))
					.isEqualTo(Set.of("productId", "favorited"));
		}
	}

	@Test
	void defaultRecommendationDocumentIsPublicAndKeepsOnlyProductsAndSource() throws Exception {
		JsonNode document = mapper.readTree(mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString());
		JsonNode operation = document.path("paths").path("/api/recommendations/default").path("get");
		assertThat(operation.has("requestBody")).isFalse();
		JsonNode envelope = resolve(document, operation.path("responses").path("200").path("content")
				.path("application/json").path("schema"));
		JsonNode data = resolve(document, envelope.path("properties").path("data"));
		assertThat(fieldNames(data.path("properties"))).isEqualTo(Set.of("items", "source"));
		JsonNode product = resolve(document, data.path("properties").path("items").path("items"));
		assertThat(fieldNames(product.path("properties"))).isEqualTo(Set.of("id", "name", "type", "price", "img", "abv", "orderCount"));
		assertThat(data.path("properties").path("source").path("enum").toString()).contains("BASELINE", "FALLBACK");
	}

	private Set<String> fieldNames(JsonNode object) {
		Set<String> names = new HashSet<>();
		object.fieldNames().forEachRemaining(names::add);
		return names;
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class NoDatabase {
		@Bean
		DataSource documentationDataSource() {
			return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(),
					new Class<?>[] {DataSource.class}, (proxy, method, args) -> switch (method.getName()) {
						case "getConnection" -> throw new AssertionError("Documentation must not query any database");
						case "isWrapperFor" -> false;
						case "unwrap" -> throw new SQLException("No underlying database in documentation test");
						case "toString" -> "Documentation-only DataSource";
						case "hashCode" -> System.identityHashCode(proxy);
						case "equals" -> proxy == args[0];
						case "getLoginTimeout" -> 0;
						default -> null;
					});
		}
	}
}
