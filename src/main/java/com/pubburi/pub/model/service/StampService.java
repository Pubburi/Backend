package com.pubburi.pub.model.service;

import java.util.List;

import com.pubburi.pub.model.dto.Stamp;

public interface StampService {

	/**
	 * 특정 사용자에 대한 스탬프 목록을 조회한다.
	 *
	 * @param userId 사용자 ID
	 * @return 스탬프 리스트
	 */
	List<Stamp> getStampsByUserId(String userId);
}
