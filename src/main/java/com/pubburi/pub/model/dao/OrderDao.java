package com.pubburi.pub.model.dao;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import com.pubburi.pub.model.dto.Order;
import com.pubburi.pub.model.dto.OrderDetailInfo;
import com.pubburi.pub.model.dto.OrderInfo;

public interface OrderDao {
	/**
	 * 특정 사용자의 주문 목록을 조회한다.
	 *
	 * @param userId 사용자 ID
	 * @return 주문 리스트
	 */
	List<Order> selectByUserId(String userId);

	/**
	 * 특정 주문 ID로 주문을 조회한다.
	 *
	 * @param oId 주문 ID
	 * @return 주문 객체
	 */
	Order selectById(int oId);

	/**
	 * 새 주문을 삽입한다.
	 *
	 * @param order 주문 정보
	 * @return 삽입 결과
	 */
	int insert(Order order);

	/**
	 * 주문 정보를 수정한다.
	 *
	 * @param order 주문 정보
	 * @return 수정 결과
	 */
	int update(Order order);

	/**
	 * 주문을 삭제한다.
	 *
	 * @param oId 주문 ID
	 * @return 삭제 결과
	 */
	int delete(int oId);

	/**
	 * 주문 ID를 기준으로 주문 정보와 함께 상세 주문 내역(details)을 포함한 확장 주문 정보를 조회한다. - OrderInfo에는 주문
	 * 정보뿐 아니라, OrderDetailInfo 리스트가 포함된다.
	 *
	 * @param oId 주문 ID
	 * @return 주문 정보 + 상세 주문 정보가 포함된 OrderInfo 객체
	 */
	OrderInfo selectOrderInfo(int oId);

	/**
	 * 특정 주문 ID에 대한 상세 주문 내역을 조회한다. - 각 상세 항목에는 상품 이름, 가격, 이미지 등 추가 정보가 포함된다.
	 *
	 * @param orderId 주문 ID
	 * @return OrderDetailInfo 리스트 (상품 정보 포함)
	 */
	List<OrderDetailInfo> getOrderDetailInfo(int orderId);

	List<OrderDetailInfo> getOrderDetailInfoByOrderIds(@Param("orderIds") List<Integer> orderIds);

	List<OrderInfo> selectOrderInfoListByUserId(String userId);

	List<OrderInfo> selectOrderInfoPageByUserId(@Param("userId") String userId, @Param("limit") int limit,
			@Param("offset") int offset);

	int countOrderInfoByUserId(String userId);

	List<OrderInfo> selectAllOrderInfo();

	List<OrderInfo> selectAllOrderInfoPaged(@Param("limit") int limit, @Param("offset") int offset);

	int countAllOrderInfo();

	/**
	 * 사용자가 주문한 최근 1개월의 주문 주문번호 내림차순으로 조회된다. 주문번호의 상세내용은 detail id의 오름차순으로 조회된다. 관통
	 * 6단계에서 사용된다.
	 *
	 * @param id
	 * @return
	 */
	List<OrderInfo> getLastMonthOrder(String id);

	/**
	 * 사용자가 주문한 최근 6개월의 주문 주문번호 내림차순으로 조회된다. 주문번호의 상세내용은 detail id의 오름차순으로 조회된다. 관통
	 * 6단계에서 사용된다.
	 *
	 * @param id
	 * @return
	 */
	List<OrderInfo> getLast6MonthOrder(String id);
}
