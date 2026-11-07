package com.pubburi.pub.model.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import com.pubburi.pub.controller.api.PageCriteria;
import com.pubburi.pub.controller.api.ProductSearchFilters;
import com.pubburi.pub.model.dao.ProductDao;
import com.pubburi.pub.model.dto.Product;

class ProductSearchTests {
	@Test
	void dataAndCountUseOneFilterPolicyAndDoNotJoinDuplicateTagRows() throws Exception {
		Configuration config = new Configuration(); config.getTypeAliasRegistry().registerAlias("Product", Product.class);
		try (InputStream xml = getClass().getResourceAsStream("/mappers/product.xml")) {
			new XMLMapperBuilder(xml, config, "mappers/product.xml", config.getSqlFragments()).parse();
		}
		Map<String, Object> input = new HashMap<>();
		input.put("type", "맥주"); input.put("q", "fixture"); input.put("sort", "priceAsc"); input.put("limit", 2); input.put("offset", 2);
		input.put("filters", new ProductSearchFilters(100, 3000, new BigDecimal("5.1"), new BigDecimal("5.55"),
				List.of("dry", "x' OR 1=1 --"), List.of("floral")));
		BoundSql items = config.getMappedStatement(ProductDao.class.getName() + ".selectFiltered").getBoundSql(input);
		BoundSql count = config.getMappedStatement(ProductDao.class.getName() + ".countFiltered").getBoundSql(input);
		String itemSql = items.getSql().replaceAll("\\s+", " ").strip();
		String countSql = count.getSql().replaceAll("\\s+", " ").strip();
		assertThat(itemSql.substring(itemSql.indexOf(" WHERE "), itemSql.indexOf(" ORDER BY ")))
				.isEqualTo(countSql.substring(countSql.indexOf(" WHERE ")));
		assertThat(itemSql).contains("p.price >= ?", "p.price <= ?", "p.abv >= CAST(? AS FLOAT)", "p.abv <= CAST(? AS FLOAT)",
				"pf.taste_known = TRUE", "pf.aroma_known = TRUE", "ft.kind = 'TASTE'", "ft.kind = 'AROMA'", "ORDER BY p.price ASC, p.id ASC");
		assertThat(itemSql).doesNotContain("x' OR", " JOIN ", "CAST(p.abv");
		assertThat(items.getParameterMappings()).hasSize(count.getParameterMappings().size() + 2);
	}

	@Test
	void productPageKeepsFiltersAndTotal() {
		List<String> calls = new ArrayList<>();
		ProductSearchFilters filters = ProductSearchFilters.fromQuery("0", "2147483647", "0", "100", null, null);
		ProductDao dao = (ProductDao) Proxy.newProxyInstance(ProductDao.class.getClassLoader(), new Class<?>[] {ProductDao.class},
				(proxy, method, args) -> {
					calls.add(method.getName());
					assertThat(args[0]).isNull(); assertThat(args[1]).isEqualTo("fixture");
					if (method.getName().equals("selectFiltered")) {
						assertThat(args[3]).isSameAs(filters); assertThat(args[4]).isEqualTo(2); assertThat(args[5]).isEqualTo(2);
						return List.of(new Product(3, "Fixture", "맥주", 1000, "fixture.png", 5.1f));
					}
					assertThat(args[2]).isSameAs(filters); return 3;
				});
		ProductServiceImpl service = new ProductServiceImpl(dao, org.mockito.Mockito.mock(com.pubburi.pub.model.service.ProductSearchOutbox.class));
		var page = service.searchProducts(" ", " fixture ", "popular", filters, PageCriteria.of(2, 2, 12));
		assertThat(page.total()).isEqualTo(3); assertThat(page.totalPages()).isEqualTo(2); assertThat(page.hasNext()).isFalse();
		assertThat(calls).containsExactly("selectFiltered", "countFiltered");
	}
}
