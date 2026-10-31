package com.pubburi.pub.model.dao;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import com.pubburi.pub.model.dto.ProductFeatures;
import com.pubburi.pub.model.dto.ProductFeatureTag;

public interface ProductFeatureDao {
	ProductFeatures selectByProductId(int productId);
	List<ProductFeatureTag> selectTags(int productId);
	int upsert(ProductFeatures features);
	int deleteTags(int productId);
	int insertTags(@Param("productId") int productId, @Param("tags") List<ProductFeatureTag> tags);
}
