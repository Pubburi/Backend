package com.pubburi.pub.controller.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Set;

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
import com.pubburi.pub.model.dto.UserPreferences;
import com.pubburi.pub.model.service.UserPreferenceService;

class UserPreferenceContractTests {
	private static final String PATH = "/api/users/me/preferences";

	@Test
	void bothOperationsRequireAnAuthenticatedCurrentSession() throws Exception {
		Fixture fixture = new Fixture("member", "USER");
		fixture.mvc().perform(get(PATH)).andExpect(status().isUnauthorized());
		fixture.mvc().perform(put(PATH).contentType(MediaType.APPLICATION_JSON).content("{\"surveyStatus\":\"SKIPPED\"}"))
				.andExpect(status().isUnauthorized());
		assertThat(fixture.calls).isZero();
	}

	@Test
	void adminAndBodyOwnerFieldsCannotSelectAnotherAccount() throws Exception {
		Fixture fixture = new Fixture("admin", "ADMIN");
		fixture.mvc().perform(put(PATH).session(fixture.session()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"surveyStatus\":\"COMPLETED\",\"userId\":\"victim\",\"authEpoch\":\"forged\"}"))
				.andExpect(status().isOk());
		assertThat(fixture.userId).isEqualTo("admin");
		assertThat(fixture.epoch).isEqualTo("fixture-admin");
	}

	@Test
	void activeUnknownResponseContainsOnlyThePrivateValueContract() throws Exception {
		Fixture fixture = new Fixture("member", "USER");
		String body = fixture.mvc().perform(get(PATH).session(fixture.session())).andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		JsonNode data = new ObjectMapper().readTree(body).get("data");
		assertThat(data.get("surveyStatus").asText()).isEqualTo("NOT_STARTED");
		assertThat(data.size()).isEqualTo(3);
		assertThat(data.get("softPreferences").size()).isEqualTo(4);
		assertThat(data.get("hardConstraints").size()).isEqualTo(4);
		assertThat(data.get("softPreferences").get("categories").isNull()).isTrue();
		assertThat(data.get("hardConstraints").get("maxPriceKrw").isNull()).isTrue();
		assertThat(data.has("userId") || data.has("authEpoch")).isFalse();
	}

	@Test
	void identityDisappearingAfterGuardReturns401InsteadOfAnUnknownSurvey() throws Exception {
		Fixture fixture = new Fixture("member", "USER"); fixture.expired = true;
		fixture.mvc().perform(get(PATH).session(fixture.session())).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
		fixture.mvc().perform(put(PATH).session(fixture.session()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"surveyStatus\":\"SKIPPED\"}")).andExpect(status().isUnauthorized());
	}

	@Test
	void invalidStateRangesAndPrecisionAreRejectedBeforeTheService() throws Exception {
		Fixture fixture = new Fixture("member", "USER");
		for (String input : List.of("{}", "{\"surveyStatus\":1}", "{\"surveyStatus\":\"UNKNOWN\"}",
				"{\"surveyStatus\":\"SKIPPED\",\"softPreferences\":{\"tasteTags\":[]}}",
				"{\"surveyStatus\":\"NOT_STARTED\",\"hardConstraints\":{\"maxPriceKrw\":0}}",
				"{\"surveyStatus\":\"COMPLETED\",\"hardConstraints\":{\"minPriceKrw\":2,\"maxPriceKrw\":1}}",
				"{\"surveyStatus\":\"COMPLETED\",\"hardConstraints\":{\"minAbvPercent\":10,\"maxAbvPercent\":5}}",
				"{\"surveyStatus\":\"COMPLETED\",\"softPreferences\":{\"targetAbvPercent\":10.123}}")) {
			fixture.mvc().perform(put(PATH).session(fixture.session()).contentType(MediaType.APPLICATION_JSON).content(input))
					.andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
		}
		assertThat(fixture.calls).isZero();
	}

	@Test
	void strictStringIntegerAndDecimalInputsCannotBeCoerced() throws Exception {
		Fixture fixture = new Fixture("member", "USER");
		for (String soft : List.of("{\"tasteTags\":[1]}", "{\"aromaTags\":[true]}", "{\"categories\":[{}]}",
				"{\"targetAbvPercent\":\"12.5\"}", "{\"targetAbvPercent\":true}", "{\"tasteTags\":[null]}")) {
			fixture.mvc().perform(put(PATH).session(fixture.session()).contentType(MediaType.APPLICATION_JSON)
					.content("{\"surveyStatus\":\"COMPLETED\",\"softPreferences\":" + soft + "}"))
					.andExpect(status().isBadRequest());
		}
		for (String price : List.of("1.5", "\"100\"", "true", "2147483648")) {
			fixture.mvc().perform(put(PATH).session(fixture.session()).contentType(MediaType.APPLICATION_JSON)
					.content("{\"surveyStatus\":\"COMPLETED\",\"hardConstraints\":{\"maxPriceKrw\":" + price + "}}"))
					.andExpect(status().isBadRequest());
		}
		assertThat(fixture.calls).isZero();
	}

	@Test
	void zeroAndSoftTargetOutsideHardRangeArePreservedAsDeclared() throws Exception {
		Fixture fixture = new Fixture("member", "USER");
		fixture.mvc().perform(put(PATH).session(fixture.session()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"surveyStatus\":\"COMPLETED\",\"softPreferences\":{\"targetAbvPercent\":80},\"hardConstraints\":{\"minPriceKrw\":0,\"maxPriceKrw\":0,\"minAbvPercent\":0,\"maxAbvPercent\":10}}"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.softPreferences.targetAbvPercent").value(80))
				.andExpect(jsonPath("$.data.hardConstraints.maxPriceKrw").value(0))
				.andExpect(jsonPath("$.data.hardConstraints.maxAbvPercent").value(10));
	}

	private static class Fixture implements UserPreferenceService {
		final String id; final String role; int calls; String userId; String epoch; boolean expired;
		Fixture(String id, String role) { this.id = id; this.role = role; }
		MockMvc mvc() { return MockMvcBuilders.standaloneSetup(new UserPreferenceRestController(this,
				SessionFixtures.guard(SessionFixtures.user(id, role)))).setControllerAdvice(new GlobalExceptionHandler()).build(); }
		MockHttpSession session() {
			MockHttpSession session = new MockHttpSession();
			session.setAttribute(AccessGuard.SESSION_USER, SessionUser.from(SessionFixtures.user(id, role))); return session;
		}
		@Override public UserPreferences getOwned(String userId, String epoch) {
			calls++; this.userId = userId; this.epoch = epoch;
			if (expired) return null;
			UserPreferences preferences = new UserPreferences(); preferences.setSurveyStatus("NOT_STARTED"); return preferences;
		}
		@Override public UserPreferences replaceOwned(String userId, String epoch, UserPreferences input) {
			calls++; this.userId = userId; this.epoch = epoch; return expired ? null : input;
		}
	}
}
