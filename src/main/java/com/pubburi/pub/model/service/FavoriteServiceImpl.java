package com.pubburi.pub.model.service;

import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.pubburi.pub.controller.api.PageCriteria;
import com.pubburi.pub.controller.api.PageResponse;
import com.pubburi.pub.model.dao.FavoriteDao;
import com.pubburi.pub.model.dao.ProductDao;
import com.pubburi.pub.model.dao.UserDao;
import com.pubburi.pub.model.dto.Product;
import com.pubburi.pub.model.dto.User;

@Service
public class FavoriteServiceImpl implements FavoriteService {
	private final FavoriteDao favoriteDao;
	private final UserDao userDao;
	private final ProductDao productDao;

	public FavoriteServiceImpl(FavoriteDao favoriteDao, UserDao userDao, ProductDao productDao) {
		this.favoriteDao = favoriteDao;
		this.userDao = userDao;
		this.productDao = productDao;
	}

	@Override
	@Transactional(readOnly = true)
	public Boolean getOwnedState(String userId, String authEpoch, int productId) {
		if (productId <= 0) throw new IllegalArgumentException("Product ID must be a positive integer");
		if (!identityPresent(userId, authEpoch)) return null;
		// One snapshot: no user row means expired; an existing user can return false.
		return favoriteDao.selectOwnedState(userId, authEpoch, productId);
	}

	@Override
	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public PageResponse<Product> getOwnedPage(String userId, String authEpoch, PageCriteria criteria) {
		if (!identityPresent(userId, authEpoch) || userDao.selectSessionIdentity(userId, authEpoch) == null) return null;
		if (criteria == null || criteria.page() < 1 || criteria.size() < 1 || criteria.size() > 100
				|| (long) (criteria.page() - 1) * criteria.size() > Integer.MAX_VALUE) {
			throw new IllegalArgumentException("Pagination offset is too large or invalid");
		}
		int total = favoriteDao.countOwned(userId, authEpoch);
		return PageResponse.of(favoriteDao.selectOwnedPage(userId, authEpoch, criteria.size(), criteria.offset()), criteria, total);
	}

	@Override
	@Transactional
	public MutationResult setOwned(String userId, String authEpoch, int productId, boolean favorited) {
		if (productId <= 0) throw new IllegalArgumentException("Product ID must be a positive integer");
		if (!identityPresent(userId, authEpoch)) return MutationResult.ACCOUNT_EXPIRED;
		User user = userDao.selectByIdForUpdate(userId);
		if (user == null || !Objects.equals(user.getAuthEpoch(), authEpoch)) return MutationResult.ACCOUNT_EXPIRED;
		if (!favorited) {
			favoriteDao.deleteOwned(userId, productId);
			return MutationResult.ABSENT;
		}
		// Keep the same User -> Product lock order as order creation.
		if (productDao.selectByIdForUpdate(productId) == null) return MutationResult.PRODUCT_MISSING;
		// A duplicate is a no-op, preserving the first created_at and list position.
		favoriteDao.ensurePresent(userId, productId);
		return MutationResult.PRESENT;
	}

	private boolean identityPresent(String userId, String authEpoch) {
		return userId != null && !userId.isBlank() && authEpoch != null && !authEpoch.isBlank();
	}
}
