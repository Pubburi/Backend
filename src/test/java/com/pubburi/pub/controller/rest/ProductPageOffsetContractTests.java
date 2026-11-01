package com.pubburi.pub.controller.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.pubburi.pub.controller.api.GlobalExceptionHandler;
import com.pubburi.pub.controller.api.ProductSearchFilters;
import com.pubburi.pub.model.dao.ProductDao;
import com.pubburi.pub.model.service.ProductServiceImpl;

class ProductPageOffsetContractTests {
	@ParameterizedTest(name = "unsupported page={0}, size={1}")
	@CsvSource({"2147483647,100", "1073741825,4", "42949674,100", "21474838,100"})
	void negativeZeroAndPositiveWrapsAreRejectedBeforeAnyProductDaoCall(int page, int size) throws Exception {
		Fixture fixture = new Fixture();
		fixture.mvc.perform(get("/api/products").param("page", Integer.toString(page)).param("size", Integer.toString(size)))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.data").isEmpty())
				.andExpect(jsonPath("$.error.code").value("BAD_REQUEST"))
				.andExpect(jsonPath("$.error.message").value("Product page offset exceeds the supported range"));
		assertThat(fixture.calls.get()).isZero();
	}

	@ParameterizedTest(name = "supported page={0}, size={1}")
	@CsvSource({"1,100,1,100,0", "1,4,1,4,0", "21474837,100,21474837,100,2147483600",
		"2147483647,1,2147483647,1,2147483646", "0,0,1,12,0", "-5,-5,1,12,0",
		"1,1000,1,100,0", "-2147483648,2147483647,1,100,0", "2,2,2,2,2"})
	void supportedOffsetsPreserveNormalizationFiltersSortAndPageContract(int page, int size, int normalizedPage,
			int normalizedSize, int offset) throws Exception {
		Fixture fixture = new Fixture();
		fixture.mvc.perform(get("/api/products").param("page", Integer.toString(page)).param("size", Integer.toString(size))
				.param("type", "맥주").param("q", "scope").param("sort", "priceDesc").param("minPriceKrw", "0")
				.param("maxPriceKrw", "1000").param("minAbvPercent", "0").param("maxAbvPercent", "5.55")
				.param("tasteTag", "dry").param("aromaTag", "floral"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.error").isEmpty())
				.andExpect(jsonPath("$.data.page").value(normalizedPage)).andExpect(jsonPath("$.data.size").value(normalizedSize))
				.andExpect(jsonPath("$.data.items").isEmpty()).andExpect(jsonPath("$.data.total").value(0))
				.andExpect(jsonPath("$.data.totalPages").value(0)).andExpect(jsonPath("$.data.hasNext").value(false));
		Object[] selected = fixture.selection.get();
		assertThat(selected[0]).isEqualTo("맥주"); assertThat(selected[1]).isEqualTo("scope");
		assertThat(selected[2]).isEqualTo("priceDesc"); assertThat(selected[4]).isEqualTo(normalizedSize);
		assertThat(selected[5]).isEqualTo(offset);
		ProductSearchFilters filters = (ProductSearchFilters) selected[3];
		assertThat(filters.minPriceKrw()).isZero(); assertThat(filters.maxPriceKrw()).isEqualTo(1000);
		assertThat(filters.minAbvPercent()).isEqualByComparingTo("0");
		assertThat(filters.maxAbvPercent()).isEqualByComparingTo("5.55");
		assertThat(filters.tasteTags()).containsExactly("dry"); assertThat(filters.aromaTags()).containsExactly("floral");
		assertThat(fixture.calls.get()).isEqualTo(2);
	}

	private static class Fixture {
		final AtomicInteger calls = new AtomicInteger();
		final AtomicReference<Object[]> selection = new AtomicReference<>();
		final MockMvc mvc;
		Fixture() {
			ProductDao dao = (ProductDao) Proxy.newProxyInstance(ProductDao.class.getClassLoader(), new Class<?>[] {ProductDao.class},
					(proxy, method, args) -> {
						calls.incrementAndGet();
						if (method.getName().equals("selectFiltered")) { selection.set(args); return List.of(); }
						if (method.getName().equals("countFiltered")) return 0;
						throw new AssertionError("Unexpected product operation");
					});
			mvc = MockMvcBuilders.standaloneSetup(new ProductRestController(new ProductServiceImpl(dao, org.mockito.Mockito.mock(com.pubburi.pub.model.service.ProductSearchOutbox.class)), null))
					.setControllerAdvice(new GlobalExceptionHandler()).build();
		}
	}
}
