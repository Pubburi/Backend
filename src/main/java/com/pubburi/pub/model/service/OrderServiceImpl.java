package com.pubburi.pub.model.service;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pubburi.pub.controller.api.PageCriteria;
import com.pubburi.pub.controller.api.PageResponse;
import com.pubburi.pub.model.dao.OrderDao;
import com.pubburi.pub.model.dao.OrderDetailDao;
import com.pubburi.pub.model.dao.ProductDao;
import com.pubburi.pub.model.dao.StampDao;
import com.pubburi.pub.model.dao.UserDao;
import com.pubburi.pub.model.dto.Order;
import com.pubburi.pub.model.dto.OrderDetail;
import com.pubburi.pub.model.dto.OrderDetailInfo;
import com.pubburi.pub.model.dto.OrderInfo;
import com.pubburi.pub.model.dto.Stamp;

@Service
public class OrderServiceImpl implements OrderService {

	private final OrderDao orderDao;
	private final OrderDetailDao orderDetailDao;
	private final StampDao stampDao;
	private final UserDao userDao;
	private final ProductDao productDao;

	public OrderServiceImpl(OrderDao orderDao, OrderDetailDao orderDetailDao, StampDao stampDao, UserDao userDao,
			ProductDao productDao) {
		this.orderDao = orderDao;
		this.orderDetailDao = orderDetailDao;
		this.stampDao = stampDao;
		this.userDao = userDao;
		this.productDao = productDao;
	}

	@Override
	@Transactional
	public int makeOrder(Order order, List<OrderDetail> details) {
		if (order == null || isBlank(order.getUserId()) || details == null || details.isEmpty()) {
			return 0;
		}
		List<OrderDetail> normalizedDetails = normalizeDetails(details);
		if (isBlank(order.getCompleted())) {
			order.setCompleted("N");
		}
		int result = orderDao.insert(order);
		if (result <= 0) {
			return 0;
		}
		int totalQuantity = 0;
		for (OrderDetail detail : normalizedDetails) {
			detail.setOrderId(order.getoId());
			if (productDao.incrementOrderCount(detail.getProductId(), detail.getQuantity()) <= 0) {
				throw new IllegalArgumentException("Product not found: " + detail.getProductId());
			}
			totalQuantity += detail.getQuantity();
		}
		if (orderDetailDao.insertAll(normalizedDetails) != normalizedDetails.size()) {
			throw new IllegalStateException("Order details could not be created");
		}
		Stamp stamp = new Stamp();
		stamp.setUserId(order.getUserId());
		stamp.setOrderId(order.getoId());
		stamp.setQuantity(totalQuantity);
		if (stampDao.insert(stamp) <= 0) {
			throw new IllegalStateException("Stamp could not be created");
		}

		if (userDao.incrementStamps(order.getUserId(), totalQuantity) <= 0) {
			throw new IllegalArgumentException("User not found: " + order.getUserId());
		}
		return result;
	}

	@Override
	public List<OrderInfo> getOrdersByUserId(String userId) {
		if (isBlank(userId)) {
			return List.of();
		}
		return attachDetails(orderDao.selectOrderInfoListByUserId(userId));
	}

	@Override
	public PageResponse<OrderInfo> getOrdersByUserId(String userId, PageCriteria criteria) {
		if (isBlank(userId)) {
			return PageResponse.of(List.of(), criteria, 0);
		}
		List<OrderInfo> orders = attachDetails(orderDao.selectOrderInfoPageByUserId(userId, criteria.size(), criteria.offset()));
		return PageResponse.of(orders, criteria, orderDao.countOrderInfoByUserId(userId));
	}

	@Override
	public List<OrderInfo> getAllOrders() {
		return attachDetails(orderDao.selectAllOrderInfo());
	}

	@Override
	public PageResponse<OrderInfo> getAllOrders(PageCriteria criteria) {
		List<OrderInfo> orders = attachDetails(orderDao.selectAllOrderInfoPaged(criteria.size(), criteria.offset()));
		return PageResponse.of(orders, criteria, orderDao.countAllOrderInfo());
	}

	@Override
	public Order getOrderById(int oId) {
		if (oId <= 0) {
			return null;
		}
		return orderDao.selectById(oId);
	}

	@Override
	@Transactional
	public int updateOrder(Order order) {
		if (order == null || order.getoId() <= 0) {
			return 0;
		}
		return orderDao.update(order);
	}

	@Override
	@Transactional
	public int removeOrder(int oId) {
		if (oId <= 0) {
			return 0;
		}
		return orderDao.delete(oId);
	}

	@Override
	public OrderInfo getOrderInfo(int oId) {
		if (oId <= 0) {
			return null;
		}
		OrderInfo order = orderDao.selectOrderInfo(oId);
		if (order == null) {
			return null;
		}
		return attachDetails(List.of(order)).get(0);
	}

	@Override
	public List<OrderInfo> getLastMonthOrder(String id) {
		return isBlank(id) ? List.of() : attachDetails(orderDao.getLastMonthOrder(id));
	}

	@Override
	public List<OrderInfo> getLast6MonthOrder(String id) {
		return isBlank(id) ? List.of() : attachDetails(orderDao.getLast6MonthOrder(id));
	}

	private List<OrderDetail> normalizeDetails(List<OrderDetail> details) {
		Map<Integer, OrderDetail> byProductId = new LinkedHashMap<>();
		for (OrderDetail detail : details) {
			if (detail == null || detail.getProductId() <= 0 || detail.getQuantity() <= 0) {
				throw new IllegalArgumentException("Invalid order detail");
			}
			byProductId.compute(detail.getProductId(), (productId, existing) -> {
				if (existing == null) {
					OrderDetail normalized = new OrderDetail();
					normalized.setProductId(productId);
					normalized.setQuantity(detail.getQuantity());
					return normalized;
				}
				existing.setQuantity(existing.getQuantity() + detail.getQuantity());
				return existing;
			});
		}
		return List.copyOf(byProductId.values());
	}

	private List<OrderInfo> attachDetails(List<OrderInfo> orders) {
		if (orders == null || orders.isEmpty()) {
			return List.of();
		}
		List<Integer> orderIds = orders.stream().map(OrderInfo::getoId).toList();
		Map<Integer, List<OrderDetailInfo>> detailsByOrderId = orderDao.getOrderDetailInfoByOrderIds(orderIds).stream()
				.collect(Collectors.groupingBy(OrderDetailInfo::getOrderId));
		for (OrderInfo order : orders) {
			order.setDetails(detailsByOrderId.getOrDefault(order.getoId(), List.of()));
		}
		return orders;
	}

	private boolean isBlank(String value) {
		return value == null || value.trim().isEmpty();
	}
}
