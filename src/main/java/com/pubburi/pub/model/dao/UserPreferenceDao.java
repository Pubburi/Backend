package com.pubburi.pub.model.dao;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import com.pubburi.pub.model.dto.UserPreferences;
import com.pubburi.pub.model.dto.UserPreferenceValue;

public interface UserPreferenceDao {
	UserPreferences selectOwned(@Param("userId") String userId, @Param("authEpoch") String authEpoch);
	List<UserPreferenceValue> selectOwnedValues(@Param("userId") String userId, @Param("authEpoch") String authEpoch);
	int upsert(UserPreferences preferences);
	int deleteValues(String userId);
	int insertValues(@Param("userId") String userId, @Param("values") List<UserPreferenceValue> values);
}
