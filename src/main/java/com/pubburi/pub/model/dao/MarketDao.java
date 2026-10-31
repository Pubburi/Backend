package com.pubburi.pub.model.dao;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import com.pubburi.pub.model.dto.Market;

public interface MarketDao {

	// 모든 마켓 조회
	List<Market> selectAll();

	List<Market> selectPaged(@Param("limit") int limit, @Param("offset") int offset);

	int countAll();

	// ID로 마켓 조회
	Market selectById(int id);

	// 마켓 추가
	int insert(Market market);

	// 마켓 정보 수정
	int update(Market market);

	// 마켓 삭제
	int delete(int id);
}
