package com.pubburi.pub.controller.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.web.server.ResponseStatusException;

import com.pubburi.pub.controller.auth.AccessGuard;
import com.pubburi.pub.controller.auth.SessionUser;
import com.pubburi.pub.controller.request.AdminUserRequest;
import com.pubburi.pub.controller.api.PageCriteria;
import com.pubburi.pub.controller.api.PageResponse;
import com.pubburi.pub.model.dto.Product;
import com.pubburi.pub.model.service.ProductService;

import jakarta.validation.Validation;
import jakarta.validation.Validator;

class ControllerBehaviorTests {
	private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

	@Test
	void adminUserRoleAcceptsOnlyKnownValues() {
		AdminUserRequest request = new AdminUserRequest("회원", null, 0, "OWNER");

		assertThat(validator.validate(request))
				.anyMatch(violation -> "role".equals(violation.getPropertyPath().toString()));
	}

	@Test
	void missingProductDeleteReturnsNotFound() {
		ProductService productService = new MissingProductService();
		AccessGuard accessGuard = new AccessGuard();
		ProductRestController controller = new ProductRestController(productService, accessGuard);
		MockHttpSession session = new MockHttpSession();
		session.setAttribute(AccessGuard.SESSION_USER, new SessionUser("admin", "관리자", 0, "ADMIN"));

		assertThatThrownBy(() -> controller.deleteProduct(404, session))
				.isInstanceOfSatisfying(ResponseStatusException.class,
						exception -> assertThat(exception.getStatusCode().value()).isEqualTo(HttpStatus.NOT_FOUND.value()));
	}

	@Test
	void currentAdminCannotDeleteOwnAccount() {
		AccessGuard accessGuard = new AccessGuard();
		UserRestController controller = new UserRestController(null, null, accessGuard, null);
		MockHttpSession session = new MockHttpSession();
		session.setAttribute(AccessGuard.SESSION_USER, new SessionUser("admin", "관리자", 0, "ADMIN"));

		assertThatThrownBy(() -> controller.deleteUser("admin", session))
				.isInstanceOfSatisfying(ResponseStatusException.class,
						exception -> assertThat(exception.getStatusCode().value()).isEqualTo(HttpStatus.CONFLICT.value()));
	}

	private static class MissingProductService implements ProductService {
		@Override public List<Product> getProductList() { return List.of(); }
		@Override public PageResponse<Product> searchProducts(String type, String q, String sort, PageCriteria criteria) {
			return PageResponse.of(List.of(), criteria, 0);
		}
		@Override public Product getProductById(int id) { return null; }
		@Override public int addProduct(Product product) { return 0; }
		@Override public int updateProduct(Product product) { return 0; }
		@Override public int removeProduct(int id) { return 0; }
		@Override public List<Product> getPopularProducts() { return List.of(); }
	}
}
