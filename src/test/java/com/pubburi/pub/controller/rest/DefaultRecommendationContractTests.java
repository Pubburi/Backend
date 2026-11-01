package com.pubburi.pub.controller.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pubburi.pub.controller.api.GlobalExceptionHandler;
import com.pubburi.pub.model.dao.ProductDao;
import com.pubburi.pub.model.dto.Product;
import com.pubburi.pub.model.service.DefaultRecommendationService;

class DefaultRecommendationContractTests {
	@Test
	void publicEndpointReturnsOnlyTheTwoFieldsAndCurrentProductSevenFields() throws Exception {
		ProductDao dao = (ProductDao) Proxy.newProxyInstance(ProductDao.class.getClassLoader(), new Class<?>[] {ProductDao.class},
				(proxy, method, args) -> List.of(new Product(1, "Fixture", "맥주", 1000, "fixture.png", 5)));
		var mvc = MockMvcBuilders.standaloneSetup(new DefaultRecommendationController(new DefaultRecommendationService(dao,
				(ids, size) -> Optional.empty()))).setControllerAdvice(new GlobalExceptionHandler()).build();
		var result = mvc.perform(get("/api/recommendations/default?userId=other&url=http://example.invalid"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.source").value("FALLBACK"))
				.andReturn().getResponse().getContentAsString();
		var data = new ObjectMapper().readTree(result).path("data");
		assertThat(data.size()).isEqualTo(2); assertThat(data.path("items").get(0).size()).isEqualTo(7);
		for (String size : List.of("0", "13", "1.5", "abc", "2147483648"))
			mvc.perform(get("/api/recommendations/default?size=" + size)).andExpect(status().isBadRequest());
	}
}
