package com.pubburi.pub.model.dao;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.pubburi.pub.model.dto.ProductFeatures;
import com.pubburi.pub.model.dto.ProductFeatureTag;

public interface ProductSearchOutboxDao {
    ProductFeatures selectFeaturesForUpdate(int productId);
    List<ProductFeatureTag> selectTagsForUpdate(int productId);
    void ensureVersion(int productId);
    int advanceVersion(int productId);
    Long selectVersionForUpdate(int productId);
    int append(@Param("productId") int productId, @Param("sourceVersion") long sourceVersion,
               @Param("deleted") boolean deleted, @Param("payload") String payload);
    int schedule(@Param("productId") int productId, @Param("sourceVersion") long sourceVersion);
}
