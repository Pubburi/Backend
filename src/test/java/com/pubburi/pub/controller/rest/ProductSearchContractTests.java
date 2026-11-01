package com.pubburi.pub.controller.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pubburi.pub.controller.api.GlobalExceptionHandler;
import com.pubburi.pub.controller.api.PageCriteria;
import com.pubburi.pub.controller.api.PageResponse;
import com.pubburi.pub.controller.api.ProductSearchFilters;
import com.pubburi.pub.model.dto.Product;
import com.pubburi.pub.model.service.ProductService;

class ProductSearchContractTests {
	@Test
	void publicFiltersKeepLiteralCommaTagsAndExistingPageAndProductShape() throws Exception {
		AtomicReference<ProductSearchFilters> seen = new AtomicReference<>();
		ProductService service = (ProductService) Proxy.newProxyInstance(ProductService.class.getClassLoader(), new Class<?>[] {ProductService.class},
				(proxy, method, args) -> {
					seen.set((ProductSearchFilters) args[3]);
					return PageResponse.of(List.of(new Product(1, "Fixture", "맥주", 1000, "fixture.png", 5.1f)), (PageCriteria) args[4], 1);
				});
		var mvc = MockMvcBuilders.standaloneSetup(new ProductRestController(service, null)).setControllerAdvice(new GlobalExceptionHandler()).build();
		String result = mvc.perform(get("/api/products").param("minPriceKrw", "0").param("maxPriceKrw", "1000")
				.param("minAbvPercent", "0").param("maxAbvPercent", "5.10").param("tasteTag", " Sweet ", "dry,fruit")
				.param("aromaTag", " FLORAL ").param("page", "0").param("size", "0"))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		assertThat(seen.get().tasteTags()).containsExactly("dry,fruit", "sweet");
		assertThat(seen.get().aromaTags()).containsExactly("floral");
		assertThat(seen.get().minPriceKrw()).isZero();
		var page = new ObjectMapper().readTree(result).path("data");
		assertThat(page.size()).isEqualTo(6); assertThat(page.path("page").intValue()).isEqualTo(1);
		assertThat(page.path("size").intValue()).isEqualTo(12); assertThat(page.path("items").get(0).size()).isEqualTo(7);
		mvc.perform(get("/api/products")).andExpect(status().isOk());
		assertThat(seen.get()).isEqualTo(ProductSearchFilters.none());
	}

	@Test
	void invalidRangesTypesAndTagsFailBeforeAnyServiceRead() throws Exception {
		ProductService service = (ProductService) Proxy.newProxyInstance(ProductService.class.getClassLoader(), new Class<?>[] {ProductService.class},
				(proxy, method, args) -> { throw new AssertionError("Invalid filter must not query products"); });
		var mvc = MockMvcBuilders.standaloneSetup(new ProductRestController(service, null)).setControllerAdvice(new GlobalExceptionHandler()).build();
		for (String price : List.of("", "-1", "1.1", "NaN", "2147483648", "1e2", " 1", "true"))
			mvc.perform(get("/api/products").param("minPriceKrw", price)).andExpect(status().isBadRequest());
		for (String abv : List.of("", "-0.1", "100.01", "5.555", "NaN", "Infinity", "1e1", " 5.1"))
			mvc.perform(get("/api/products").param("maxAbvPercent", abv)).andExpect(status().isBadRequest());
		mvc.perform(get("/api/products").param("minPriceKrw", "100").param("maxPriceKrw", "99")).andExpect(status().isBadRequest());
		mvc.perform(get("/api/products").param("minAbvPercent", "5.2").param("maxAbvPercent", "5.1")).andExpect(status().isBadRequest());
		for (String tag : List.of("", "   ", "x".repeat(41)))
			mvc.perform(get("/api/products").param("tasteTag", tag)).andExpect(status().isBadRequest());
		mvc.perform(get("/api/products").param("aromaTag", "Floral", " floral ")).andExpect(status().isBadRequest());
		mvc.perform(get("/api/products").param("tasteTag", java.util.stream.IntStream.range(0, 21).mapToObj(i -> "tag" + i).toArray(String[]::new)))
				.andExpect(status().isBadRequest());
	}
}
