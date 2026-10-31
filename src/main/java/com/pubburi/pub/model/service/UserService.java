package com.pubburi.pub.model.service;

import java.util.List;

import com.pubburi.pub.controller.api.PageCriteria;
import com.pubburi.pub.controller.api.PageResponse;
import com.pubburi.pub.model.dto.User;

public interface UserService {
	List<User> getUserList();

	PageResponse<User> getUserPage(PageCriteria criteria);

	int addUser(User user);

	int modifyUser(User user);

	int removeUser(String id);

	int updateStamp(User user);

	User getUserById(String userId);

	User login(User user);

	boolean isUsedId(String id);
}
