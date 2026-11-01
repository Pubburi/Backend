package com.pubburi.pub.model.service;

import java.util.HashMap;
import java.util.List;

import org.springframework.stereotype.Service;

import com.pubburi.pub.controller.response.DefaultRecommendationResponse;
import com.pubburi.pub.controller.response.DefaultRecommendationResponse.Source;
import com.pubburi.pub.controller.response.ResponseMapper;
import com.pubburi.pub.model.dao.ProductDao;
import com.pubburi.pub.model.dto.Product;
import com.pubburi.pub.recommendation.BaselineGateway;

@Service
public class DefaultRecommendationService {
	public static final int MAX_CANDIDATES = 1000;
	private final ProductDao products;
	private final BaselineGateway gateway;
	public DefaultRecommendationService(ProductDao products, BaselineGateway gateway) {
		this.products = products; this.gateway = gateway;
	}

	// Deliberately no DB transaction around the remote call.
	public DefaultRecommendationResponse getDefaults(int size) {
		if (size < 1 || size > 12) throw new IllegalArgumentException("Size must be between 1 and 12");
		List<Integer> candidates = products.selectDefaultCandidates(MAX_CANDIDATES).stream().map(Product::getId).toList();
		if (candidates.isEmpty()) return response(List.of(), Source.FALLBACK);
		var selection = gateway.recommend(candidates, size);
		if (selection.isPresent()) {
			List<Integer> ids = selection.get();
			// Validate the local contract too; a custom gateway must never select arbitrary IDs.
			if (ids.equals(candidates.subList(0, Math.min(size, candidates.size())))) {
				var current = new HashMap<Integer, Product>();
				for (Product product : products.selectCurrentByIds(ids)) current.put(product.getId(), product);
				if (current.size() == ids.size() && ids.stream().allMatch(current::containsKey))
					return response(ids.stream().map(current::get).toList(), Source.BASELINE);
			}
		}
		return response(products.selectDefaultCandidates(size), Source.FALLBACK);
	}

	private DefaultRecommendationResponse response(List<Product> rows, Source source) {
		return new DefaultRecommendationResponse(rows.stream().map(ResponseMapper::product).toList(), source);
	}
}
