package com.pubburi.pub.controller.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pubburi.pub.controller.api.GlobalExceptionHandler;
import com.pubburi.pub.controller.api.PageCriteria;
import com.pubburi.pub.controller.api.PageResponse;
import com.pubburi.pub.controller.auth.AccessGuard;
import com.pubburi.pub.controller.auth.SessionFixtures;
import com.pubburi.pub.controller.auth.SessionUser;
import com.pubburi.pub.model.dto.Product;
import com.pubburi.pub.model.service.FavoriteService;

class FavoriteContractTests {
	private static final String PATH = "/api/users/me/favorites";

	@Test
	void allOperationsRequireCurrentAuthentication() throws Exception {
		Fixture fixture = new Fixture();
		fixture.mvc().perform(get(PATH)).andExpect(status().isUnauthorized());
		fixture.mvc().perform(put(PATH + "/7")).andExpect(status().isUnauthorized());
		fixture.mvc().perform(delete(PATH + "/7")).andExpect(status().isUnauthorized());
		assertThat(fixture.calls).isZero();
	}

	@Test
	void adminStillUsesOnlyTheirOwnIdentityAndCurrentProductContract() throws Exception {
		Fixture fixture = new Fixture();
		String body = fixture.mvc().perform(get(PATH + "?userId=victim&page=0&size=999").session(fixture.session()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.page").value(1))
				.andExpect(jsonPath("$.data.size").value(100)).andReturn().getResponse().getContentAsString();
		assertThat(fixture.owner).isEqualTo("admin"); assertThat(fixture.epoch).isEqualTo("fixture-admin");
		var product = new ObjectMapper().readTree(body).path("data").path("items").get(0);
		assertThat(product.size()).isEqualTo(7); assertThat(product.path("price").asInt()).isEqualTo(12345);
	}

	@Test
	void explicitPutAndDeleteReturnTheDesiredStateWithoutABodySelector() throws Exception {
		Fixture fixture = new Fixture();
		fixture.mvc().perform(put(PATH + "/7").session(fixture.session()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"userId\":\"victim\"}"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.productId").value(7))
				.andExpect(jsonPath("$.data.favorited").value(true));
		assertThat(fixture.owner).isEqualTo("admin");
		fixture.mvc().perform(delete(PATH + "/7").session(fixture.session()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.favorited").value(false));
	}

	@Test
	void expiredIdentityIs401AndMissingPutTargetIs404() throws Exception {
		Fixture fixture = new Fixture(); fixture.expired = true;
		fixture.mvc().perform(get(PATH).session(fixture.session())).andExpect(status().isUnauthorized());
		fixture.mvc().perform(put(PATH + "/7").session(fixture.session())).andExpect(status().isUnauthorized());
		fixture.expired = false; fixture.missing = true;
		fixture.mvc().perform(put(PATH + "/7").session(fixture.session())).andExpect(status().isNotFound());
		fixture.mvc().perform(delete(PATH + "/7").session(fixture.session())).andExpect(status().isOk());
	}

	@Test
	void malformedPathAndQueryNumbersAreClientErrorsWithoutServiceCalls() throws Exception {
		Fixture fixture = new Fixture();
		for (String id : List.of("abc", "1.5", "-1", "0", "2147483648")) {
			fixture.mvc().perform(put(PATH + "/" + id).session(fixture.session())).andExpect(status().isBadRequest());
			fixture.mvc().perform(delete(PATH + "/" + id).session(fixture.session())).andExpect(status().isBadRequest());
		}
		for (String value : List.of("abc", "1.5", "true", "2147483648")) {
			fixture.mvc().perform(get(PATH + "?page=" + value).session(fixture.session())).andExpect(status().isBadRequest());
		}
		assertThat(fixture.calls).isZero();
	}

	@Test
	void unavailableResponseMediaUsesTheExistingJson406Envelope() throws Exception {
		Fixture fixture = new Fixture();
		fixture.mvc().perform(get(PATH).session(fixture.session()).accept(MediaType.TEXT_PLAIN))
				.andExpect(status().isNotAcceptable()).andExpect(jsonPath("$.error.code").value("NOT_ACCEPTABLE"));
	}

	@Test
	void togglePostIsRejectedWithA405EnvelopeAndAllowedMethods() throws Exception {
		Fixture fixture = new Fixture();
		var response = fixture.mvc().perform(post(PATH + "/7").session(fixture.session()))
				.andExpect(status().isMethodNotAllowed()).andExpect(jsonPath("$.error.code").value("METHOD_NOT_ALLOWED"))
				.andReturn().getResponse();
		assertThat(response.getHeader("Allow")).contains("PUT", "DELETE");
		assertThat(fixture.calls).isZero();
	}

	private static class Fixture implements FavoriteService {
		int calls; String owner; String epoch; boolean expired; boolean missing;
		MockMvc mvc() { return MockMvcBuilders.standaloneSetup(new FavoriteRestController(this,
				SessionFixtures.guard(SessionFixtures.user("admin", "ADMIN")))).setControllerAdvice(new GlobalExceptionHandler()).build(); }
		MockHttpSession session() {
			MockHttpSession session = new MockHttpSession();
			session.setAttribute(AccessGuard.SESSION_USER, SessionUser.from(SessionFixtures.user("admin", "ADMIN"))); return session;
		}
		@Override public PageResponse<Product> getOwnedPage(String id, String epoch, PageCriteria criteria) {
			calls++; owner = id; this.epoch = epoch; if (expired) return null;
			Product product = new Product(); product.setId(7); product.setName("Current product"); product.setPrice(12345);
			return PageResponse.of(List.of(product), criteria, 1);
		}
		@Override public MutationResult setOwned(String id, String epoch, int productId, boolean desired) {
			calls++; owner = id; this.epoch = epoch;
			if (expired) return MutationResult.ACCOUNT_EXPIRED;
			if (desired && missing) return MutationResult.PRODUCT_MISSING;
			return desired ? MutationResult.PRESENT : MutationResult.ABSENT;
		}
	}
}
