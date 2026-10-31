package com.pubburi.pub.model.service;

import com.pubburi.pub.model.dto.ProductFeatures;

public interface ProductFeatureService {
	ProductFeatures getProductFeatures(int productId);
	ProductFeatures replaceProductFeatures(int productId, ProductFeatures changes);
}
