package com.pubburi.pub.model.service;

import com.pubburi.pub.controller.api.PageCriteria;
import com.pubburi.pub.controller.api.PageResponse;
import com.pubburi.pub.model.dto.Product;

public interface FavoriteService {
	enum MutationResult { PRESENT, ABSENT, ACCOUNT_EXPIRED, PRODUCT_MISSING }
	PageResponse<Product> getOwnedPage(String userId, String authEpoch, PageCriteria criteria);
	MutationResult setOwned(String userId, String authEpoch, int productId, boolean favorited);
}
