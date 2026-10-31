package com.pubburi.pub.controller.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pubburi.pub.controller.api.GlobalExceptionHandler;
import com.pubburi.pub.controller.auth.AccessGuard;
import com.pubburi.pub.controller.auth.SessionFixtures;
import com.pubburi.pub.controller.auth.SessionUser;
import com.pubburi.pub.controller.request.ProductFeaturesRequest;
import com.pubburi.pub.model.dto.ProductFeatures;
import com.pubburi.pub.model.service.ProductFeatureService;

import jakarta.validation.Validation;
import jakarta.validation.Validator;

class ProductFeatureContractTests {
	private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

	@Test
	void publicReadKeepsUnknownFieldsExplicitlyNull() throws Exception {
		Fixture fixture = new Fixture();
		String body = fixture.mvc().perform(get("/api/products/7/features")).andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		JsonNode response = new ObjectMapper().readTree(body).get("data");
		assertThat(response.size()).isEqualTo(8);
		assertThat(response.get("productId").intValue()).isEqualTo(7);
		for (String field : List.of("volumeMl", "description", "tasteTags", "aromaTags", "sourceName", "sourceUrl", "sourceNote")) {
			assertThat(response.get(field).isNull()).as(field).isTrue();
		}
	}

	@Test
	void onlyCurrentAdminCanReplaceAttributes() throws Exception {
		Fixture fixture = new Fixture();
		fixture.mvc().perform(put("/api/admin/products/7/features").contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
		MockHttpSession user = session("member", "USER");
		fixture.mvcFor("member", "USER").perform(put("/api/admin/products/7/features").session(user)
				.contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
		assertThat(fixture.replacements).isZero();
		fixture.mvc().perform(put("/api/admin/products/7/features").session(session("admin", "ADMIN"))
				.contentType(MediaType.APPLICATION_JSON).content("{\"tasteTags\":[],\"sourceName\":\"Fixture\"}"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.tasteTags").isEmpty());
		assertThat(fixture.replacements).isEqualTo(1);
	}

	@Test
	void missingProductReturnsTheExistingApiErrorEnvelope() throws Exception {
		Fixture fixture = new Fixture();
		fixture.exists = false;
		fixture.mvc().perform(get("/api/products/404/features"))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
		fixture.mvc().perform(put("/api/admin/products/404/features").session(session("admin", "ADMIN"))
				.contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isNotFound());
	}

	@Test
	void reviewedEmptyTagsAndKnownVolumeRequireSourceButUnknownDoesNot() {
		assertThat(validator.validate(new ProductFeaturesRequest(null, null, null, null, null, null, null))).isEmpty();
		assertThat(validator.validate(new ProductFeaturesRequest(null, null, List.of(), null, null, null, null))).isNotEmpty();
		assertThat(validator.validate(new ProductFeaturesRequest(750, null, null, null, " ", null, null))).isNotEmpty();
		assertThat(validator.validate(new ProductFeaturesRequest(null, null, List.of(), null, "Evidence", null, null))).isEmpty();
	}

	@Test
	void fieldBoundsAndUnsafeSourceSchemesAreRejected() {
		assertThat(validator.validate(new ProductFeaturesRequest(0, null, null, null, "Evidence", null, null))).isNotEmpty();
		assertThat(validator.validate(new ProductFeaturesRequest(1_000_001, null, null, null, "Evidence", null, null))).isNotEmpty();
		assertThat(validator.validate(new ProductFeaturesRequest(750, "x".repeat(2001), null, null, "Evidence", null, null))).isNotEmpty();
		assertThat(validator.validate(new ProductFeaturesRequest(null, null, List.of(" "), null, "Evidence", null, null))).isNotEmpty();
		assertThat(validator.validate(new ProductFeaturesRequest(null, null, List.of("x".repeat(41)), null, "Evidence", null, null))).isNotEmpty();
		for (String url : List.of("file:///tmp/source", "javascript:alert(1)", "/relative", "https://user:pass@example.com/source")) {
			assertThat(validator.validate(new ProductFeaturesRequest(null, null, null, null, null, url, null))).as(url).isNotEmpty();
		}
		assertThat(validator.validate(new ProductFeaturesRequest(null, null, null, null, null, "https://example.com/source#facts", null))).isEmpty();
	}

	@Test
	void fractionalAndStringVolumesReturn400InsteadOfCoercionOr500() throws Exception {
		Fixture fixture = new Fixture();
		for (String quantity : List.of("750.5", "\"750\"", "true", "2147483648")) {
			fixture.mvc().perform(put("/api/admin/products/7/features").session(session("admin", "ADMIN"))
					.contentType(MediaType.APPLICATION_JSON).content("{\"volumeMl\":" + quantity + ",\"sourceName\":\"Evidence\"}"))
					.andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
		}
		assertThat(fixture.replacements).isZero();
	}

	@Test
	void invalidContainerItemsAndMissingEvidenceUseValidationEnvelope() throws Exception {
		Fixture fixture = new Fixture();
		for (String input : List.of("{\"tasteTags\":[]}", "{\"tasteTags\":[null],\"sourceName\":\"Fixture\"}",
				"{\"aromaTags\":[\" \"],\"sourceName\":\"Fixture\"}", "{\"sourceUrl\":\"ftp://example.com/source\"}")) {
			fixture.mvc().perform(put("/api/admin/products/7/features").session(session("admin", "ADMIN"))
					.contentType(MediaType.APPLICATION_JSON).content(input))
					.andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
		}
		assertThat(fixture.replacements).isZero();
	}

	@Test
	void nonStringTagInputsCannotBeCoercedIntoDeclaredProductFacts() throws Exception {
		Fixture fixture = new Fixture();
		List<Integer> statuses = new java.util.ArrayList<>();
		for (String value : List.of("1", "true", "null", "{}", "[\"nested\"]")) {
			statuses.add(fixture.mvc().perform(put("/api/admin/products/7/features").session(session("admin", "ADMIN"))
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"tasteTags\":[" + value + "],\"sourceName\":\"Fixture\"}"))
					.andReturn().getResponse().getStatus());
		}
		assertThat(statuses).containsExactly(400, 400, 400, 400, 400);
		assertThat(fixture.replacements).isZero();
	}

	@Test
	void scalarProvenanceAndDescriptionAlsoRequireJsonStrings() throws Exception {
		Fixture fixture = new Fixture();
		for (String input : List.of("{\"description\":true,\"sourceName\":\"Fixture\"}",
				"{\"sourceName\":42,\"tasteTags\":[]}", "{\"sourceUrl\":123}", "{\"sourceNote\":{}}")) {
			fixture.mvc().perform(put("/api/admin/products/7/features").session(session("admin", "ADMIN"))
					.contentType(MediaType.APPLICATION_JSON).content(input))
					.andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
		}
		assertThat(fixture.replacements).isZero();
	}

	private static MockHttpSession session(String id, String role) {
		MockHttpSession session = new MockHttpSession();
		session.setAttribute(AccessGuard.SESSION_USER, SessionUser.from(SessionFixtures.user(id, role)));
		return session;
	}

	private static class Fixture implements ProductFeatureService {
		boolean exists = true;
		int replacements;
		MockMvc mvc() { return mvcFor("admin", "ADMIN"); }
		MockMvc mvcFor(String id, String role) {
			AccessGuard guard = SessionFixtures.guard(SessionFixtures.user(id, role));
			return MockMvcBuilders.standaloneSetup(new ProductFeatureRestController(this, guard))
					.setControllerAdvice(new GlobalExceptionHandler()).build();
		}
		@Override public ProductFeatures getProductFeatures(int id) {
			if (!exists) return null;
			ProductFeatures features = new ProductFeatures();
			features.setProductId(id); return features;
		}
		@Override public ProductFeatures replaceProductFeatures(int id, ProductFeatures changes) {
			replacements++;
			if (!exists) return null;
			changes.setProductId(id); return changes;
		}
	}
}
