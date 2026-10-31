package com.pubburi.pub.model.service;

import com.pubburi.pub.model.dto.UserPreferences;

public interface UserPreferenceService {
	UserPreferences getOwned(String userId, String authEpoch);
	UserPreferences replaceOwned(String userId, String authEpoch, UserPreferences preferences);
}
