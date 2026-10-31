package com.pubburi.pub.model.dao;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import com.pubburi.pub.model.dto.User;

public interface UserDao {
	List<User> selectAll();

	List<User> selectPaged(@Param("limit") int limit, @Param("offset") int offset);

	int countAll();

	int insert(User user);

	int update(User user);

	int upgradePassword(@Param("id") String id, @Param("expectedHash") String expectedHash,
			@Param("authEpoch") String authEpoch, @Param("pass") String pass);

	int delete(String id);

	int clearOrderNotes(@Param("id") String id);

	int updateStamp(User user);

	int incrementStamps(@Param("id") String id, @Param("quantity") int quantity);

	User selectById(String userId);

	User selectByIdForUpdate(@Param("userId") String userId);

	User selectSessionIdentity(@Param("id") String id, @Param("authEpoch") String authEpoch);
}
