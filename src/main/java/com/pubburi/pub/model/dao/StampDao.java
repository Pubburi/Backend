package com.pubburi.pub.model.dao;

import java.util.List;

import com.pubburi.pub.model.dto.Stamp;

public interface StampDao {

	/**
	 * 특정 사용자에 대한 스탬프 목록을 조회한다.
	 *
	 * @param userId 사용자 ID
	 * @return 스탬프 리스트
	 */
	List<Stamp> selectByUserId(String userId);

	/**
	 * 스탬프를 적립한다.
	 *
	 * @param stamp 스탬프 정보
	 * @return 삽입 결과
	 */
	int insert(Stamp stamp);
}
