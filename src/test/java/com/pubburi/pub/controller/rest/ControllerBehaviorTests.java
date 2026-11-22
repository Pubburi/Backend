package com.pubburi.pub.controller.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pubburi.pub.controller.auth.AccessGuard;
import com.pubburi.pub.controller.auth.SessionUser;
import com.pubburi.pub.controller.auth.SessionFixtures;
import com.pubburi.pub.controller.request.AdminUserRequest;
import com.pubburi.pub.controller.request.ProductRequest;
import com.pubburi.pub.controller.request.ProductFeaturesRequest;
import com.pubburi.pub.controller.api.PageCriteria;
import com.pubburi.pub.controller.api.PageResponse;
import com.pubburi.pub.model.dto.Product;
import com.pubburi.pub.model.service.ProductService;

import jakarta.validation.Validation;
import jakarta.validation.Validator;

class ControllerBehaviorTests {
	private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
	private final ObjectMapper json = new ObjectMapper();

	@Test
	void productIntegerFieldsRejectFractionsStringsAndOutOfRangeValues() throws Exception {
		for (String field : List.of("price", "orderCount")) {
			for (String token : List.of("1200.9", "1200.0", "1e3", "\"1200\"", "2147483648", "-2147483649")) {
				ObjectNode input = json.valueToTree(Map.of("name", "상품", "type", "증류주", "price", 1200,
						"img", "authored.png", "abv", 15.5, "orderCount", 0));
				input.set(field, json.readTree(token));
				assertThatThrownBy(() -> json.treeToValue(input, ProductRequest.class))
						.isInstanceOf(JsonProcessingException.class);
			}
		}
	}

	@Test
	void productIntegerBoundsAndExistingNullOrOmittedDefaultsRemainUnchanged() throws Exception {
		ObjectNode input = json.valueToTree(Map.of("name", "상품", "type", "증류주", "price", 1,
				"img", "authored.png", "abv", 15.5, "orderCount", 0));
		assertThat(validator.validate(json.treeToValue(input, ProductRequest.class))).isEmpty();
		input.put("price", Integer.MAX_VALUE);input.put("orderCount", Integer.MAX_VALUE);
		ProductRequest maximum = json.treeToValue(input, ProductRequest.class);
		assertThat(maximum.price()).isEqualTo(Integer.MAX_VALUE);
		assertThat(maximum.orderCount()).isEqualTo(Integer.MAX_VALUE);
		assertThat(validator.validate(maximum)).isEmpty();
		input.put("orderCount", -1);
		assertThat(validator.validate(json.treeToValue(input, ProductRequest.class)))
				.anyMatch(violation -> "orderCount".equals(violation.getPropertyPath().toString()));
		input.putNull("orderCount");
		assertThat(json.treeToValue(input, ProductRequest.class).orderCount()).isZero();
		assertThat(validator.validate(json.treeToValue(input, ProductRequest.class))).isEmpty();
		input.remove("orderCount");
		assertThat(json.treeToValue(input, ProductRequest.class).orderCount()).isZero();
		assertThat(validator.validate(json.treeToValue(input, ProductRequest.class))).isEmpty();
		for (String mode : List.of("null", "omitted", "zero", "negative")) {
			ObjectNode invalid = input.deepCopy();
			switch (mode) {
				case "null" -> invalid.putNull("price");
				case "omitted" -> invalid.remove("price");
				case "zero" -> invalid.put("price", 0);
				case "negative" -> invalid.put("price", -1);
			}
			assertThat(validator.validate(json.treeToValue(invalid, ProductRequest.class)))
					.anyMatch(violation -> "price".equals(violation.getPropertyPath().toString()));
		}
	}

	@Test
	void productPrimitiveDefaultsRespectStrictNullModeAndLeaveNullableVolumesUnknown() throws Exception {
		ObjectMapper strict = json.copy().enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);
		ObjectNode warm = json.valueToTree(Map.of("name", "상품", "type", "증류주", "price", 1200,
				"img", "authored.png", "abv", 15.5));
		warm.putNull("orderCount");
		assertThat(json.treeToValue(warm, ProductRequest.class).orderCount()).isZero();
		var strictReader = json.readerFor(ProductRequest.class).with(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);
		for (String field : List.of("price", "orderCount")) {
			for (String mode : List.of("null", "omitted")) {
				ObjectNode input = json.valueToTree(Map.of("name", "상품", "type", "증류주", "price", 1200,
						"img", "authored.png", "abv", 15.5, "orderCount", 0));
				if (mode.equals("null")) input.putNull(field); else input.remove(field);
				assertThatThrownBy(() -> strict.treeToValue(input, ProductRequest.class))
						.isInstanceOf(JsonProcessingException.class);
				assertThatThrownBy(() -> strictReader.readValue(input.toString()))
						.isInstanceOf(JsonProcessingException.class);
			}
		}
		for (var mapper : List.of(json, strict)) {
			for (String input : List.of("{\"volumeMl\":null}", "{}")) {
				assertThat(mapper.readValue(input, ProductFeaturesRequest.class).volumeMl()).isNull();
			}
		}
	}

	@Test
	void adminUserRoleAcceptsOnlyKnownValues() {
		AdminUserRequest request = new AdminUserRequest("회원", null, 0, "OWNER");

		assertThat(validator.validate(request))
				.anyMatch(violation -> "role".equals(violation.getPropertyPath().toString()));
	}

	@Test
	void adminUserNameIsOptionalButProvidedTextCannotBeOnlyWhitespace() {
		assertThat(validator.validate(new AdminUserRequest(null, null, 7, null))).isEmpty();
		for (String valid : List.of("회원", "!", " \n회원\r\n! ", "\uFEFF회원\u00A0", "가".repeat(100))) {
			assertThat(validator.validate(new AdminUserRequest(valid, null, null, null))).isEmpty();
		}
		for (String blank : List.of("", " \t\r\n", "\u3000", "\u00A0", "\u0085", "\u2007\u202F",
				"\u2028\u2029", "\u001C", "\uFEFF", "\uFEFF\u3000\u00A0\t\n")) {
			assertThat(validator.validate(new AdminUserRequest(blank, "replacement", 99, "ADMIN")))
					.anyMatch(violation -> "name".equals(violation.getPropertyPath().toString()));
		}
		assertThat(validator.validate(new AdminUserRequest("가".repeat(101), null, null, null)))
				.anyMatch(violation -> "name".equals(violation.getPropertyPath().toString()));
	}

	@Test
	void missingProductDeleteReturnsNotFound() {
		ProductService productService = new MissingProductService();
		AccessGuard accessGuard = SessionFixtures.guard(SessionFixtures.user("admin", "ADMIN"));
		ProductRestController controller = new ProductRestController(productService, accessGuard, null);
		MockHttpSession session = new MockHttpSession();
		session.setAttribute(AccessGuard.SESSION_USER, SessionUser.from(SessionFixtures.user("admin", "ADMIN")));

		assertThatThrownBy(() -> controller.deleteProduct(404, session))
				.isInstanceOfSatisfying(ResponseStatusException.class,
						exception -> assertThat(exception.getStatusCode().value()).isEqualTo(HttpStatus.NOT_FOUND.value()));
	}

	@Test
	void currentAdminCannotDeleteOwnAccount() {
		AccessGuard accessGuard = SessionFixtures.guard(SessionFixtures.user("admin", "ADMIN"));
		UserRestController controller = new UserRestController(null, null, accessGuard, null);
		MockHttpSession session = new MockHttpSession();
		session.setAttribute(AccessGuard.SESSION_USER, SessionUser.from(SessionFixtures.user("admin", "ADMIN")));

		assertThatThrownBy(() -> controller.deleteUser("admin", session))
				.isInstanceOfSatisfying(ResponseStatusException.class,
						exception -> assertThat(exception.getStatusCode().value()).isEqualTo(HttpStatus.CONFLICT.value()));
	}

	private static class MissingProductService implements ProductService {
		@Override public List<Product> getProductList() { return List.of(); }
		@Override public PageResponse<Product> searchProducts(String type, String q, String sort, com.pubburi.pub.controller.api.ProductSearchFilters filters, PageCriteria criteria) {
			return PageResponse.of(List.of(), criteria, 0);
		}
		@Override public List<com.pubburi.pub.model.dto.ProductSuggestion> suggestByName(String query) { return List.of(); }
		@Override public Product getProductById(int id) { return null; }
		@Override public int addProduct(Product product) { return 0; }
		@Override public int updateProduct(Product product) { return 0; }
		@Override public int removeProduct(int id) { return 0; }
		@Override public List<Product> getPopularProducts() { return List.of(); }
	}
}
