package com.pubburi.pub.model.service;

import java.util.List;

import com.pubburi.pub.controller.api.PageCriteria;
import com.pubburi.pub.controller.api.PageResponse;
import com.pubburi.pub.model.dto.User;
import com.pubburi.pub.model.dto.UserUpdate;
import com.pubburi.pub.model.dto.UserUpdateResult;

public interface UserService {
	List<User> getUserList();

	PageResponse<User> getUserPage(PageCriteria criteria);

	int addUser(User user);

	UserUpdateResult modifyUser(String id, UserUpdate changes);

	boolean modifyOwnProfile(String id, String authEpoch, String name);

	UserUpdateResult modifyOwnPassword(String id, String authEpoch, String currentPassword, String newPassword);

	final class CurrentPasswordMismatchException extends RuntimeException {
		public CurrentPasswordMismatchException() { super("Current password does not match"); }
	}

	int removeUser(String id);

	int updateStamp(User user);

	User getUserById(String userId);

	User login(User user);

	boolean isUsedId(String id);
}
