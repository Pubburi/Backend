package com.pubburi.pub.model.dao;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import com.pubburi.pub.model.dto.OrderDetail;

public interface OrderDetailDao {
	/**
	 * 특정 주문 ID에 해당하는 상세 주문 목록을 조회한다.
	 *
	 * @param orderId 주문 ID
	 * @return 주문 상세 리스트
	 */
	List<OrderDetail> selectByOrderId(int orderId);

	/**
	 * 상세 주문을 삽입한다.
	 *
	 * @param orderDetail 주문 상세 정보
	 * @return 삽입 결과
	 */
	int insert(OrderDetail orderDetail);

	int insertAll(@Param("details") List<OrderDetail> details);

	/**
	 * 특정 주문 ID에 해당하는 상세 주문을 삭제한다.
	 *
	 * @param orderId 주문 ID
	 * @return 삭제 결과
	 */
	int deleteByOrderId(int orderId);
}
