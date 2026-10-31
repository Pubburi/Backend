package com.pubburi.pub.model.service;

import java.util.List;

import com.pubburi.pub.controller.api.PageCriteria;
import com.pubburi.pub.controller.api.PageResponse;
import com.pubburi.pub.model.dto.Order;
import com.pubburi.pub.model.dto.OrderDetail;
import com.pubburi.pub.model.dto.OrderInfo;

public interface OrderService {

	/**
	 * 주문을 생성하고 주문 상세를 함께 등록한다.
	 *
	 * @param order 주문 객체 (내부에 OrderDetail 리스트 포함)
	 * @return 처리 결과
	 */
	int makeOrder(Order order, List<OrderDetail> details);

	/**
	 * 특정 사용자 ID의 모든 주문 목록을 조회한다.
	 *
	 * @param userId 사용자 ID
	 * @return 주문 리스트
	 */
	List<OrderInfo> getOrdersByUserId(String userId);

	PageResponse<OrderInfo> getOrdersByUserId(String userId, PageCriteria criteria);

	List<OrderInfo> getAllOrders();

	PageResponse<OrderInfo> getAllOrders(PageCriteria criteria);

	/**
	 * 특정 주문 ID로 주문을 조회한다.
	 *
	 * @param oId 주문 ID
	 * @return 주문 객체
	 */
	Order getOrderById(int oId);

	/**
	 * 주문 정보를 수정한다.
	 *
	 * @param order 주문 정보
	 * @return 수정 결과
	 */
	int updateOrder(Order order);

	/**
	 * 주문을 삭제한다.
	 *
	 * @param oId 주문 ID
	 * @return 삭제 결과
	 */
	int removeOrder(int oId);

	/**
	 * 주문 ID를 기반으로 주문 정보와 상세 항목(Product 정보 포함)을 조회한다. - 단순 Order가 아닌, 확장된 OrderInfo
	 * 객체를 반환한다. - 상세 항목에는 상품 이름, 가격, 이미지, 총합 가격 등의 정보가 포함된다.
	 *
	 * @param oId 주문 ID
	 * @return OrderInfo 객체 (상세 항목 포함)
	 */
	OrderInfo getOrderInfo(int oId);

	/**
	 * 최근 1개월의 주문 내역을 반환한다. 주문번호의 내림차순으로 조회된다. 주문번호로 조회된 order detail은 상세의 detail
	 * id의 오름차순으로 조회된다. 관통 6단계에서 추가됨
	 *
	 * @param id
	 * @return
	 */
	List<OrderInfo> getLastMonthOrder(String id);

	/**
	 * 최근 6개월의 주문 내역을 반환한다. 주문번호로 조회된 order detail은 상세의 detail id의 오름차순으로 조회된다. 관통
	 * 6단계에서 추가됨
	 *
	 * @param id
	 * @return
	 */
	List<OrderInfo> getLast6MonthOrder(String id);
}
