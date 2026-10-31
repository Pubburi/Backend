package com.pubburi.pub.model.dao;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import com.pubburi.pub.model.dto.Product;

public interface FavoriteDao {
	List<Product> selectOwnedPage(@Param("userId") String userId, @Param("authEpoch") String authEpoch,
			@Param("limit") int limit, @Param("offset") int offset);
	int countOwned(@Param("userId") String userId, @Param("authEpoch") String authEpoch);
	int ensurePresent(@Param("userId") String userId, @Param("productId") int productId);
	int deleteOwned(@Param("userId") String userId, @Param("productId") int productId);
}
