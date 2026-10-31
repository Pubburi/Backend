package com.pubburi.pub.controller.rest;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.pubburi.pub.controller.api.ApiResponse;
import com.pubburi.pub.controller.api.PageCriteria;
import com.pubburi.pub.controller.api.PageResponse;
import com.pubburi.pub.controller.auth.AccessGuard;
import com.pubburi.pub.controller.auth.SessionUser;
import com.pubburi.pub.controller.request.OrderCreateRequest;
import com.pubburi.pub.controller.response.OrderResponse;
import com.pubburi.pub.controller.response.ResponseMapper;
import com.pubburi.pub.model.dto.Order;
import com.pubburi.pub.model.dto.OrderDetail;
import com.pubburi.pub.model.dto.OrderInfo;
import com.pubburi.pub.model.service.OrderService;

import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api")
public class OrderRestController {

	private final OrderService orderService;
	private final AccessGuard accessGuard;

	public OrderRestController(OrderService orderService, AccessGuard accessGuard) {
		this.orderService = orderService;
		this.accessGuard = accessGuard;
	}

	@PostMapping("/orders")
	public ResponseEntity<ApiResponse<OrderResponse>> makeOrder(@Valid @RequestBody OrderCreateRequest request,
			HttpSession session) {
		SessionUser user = accessGuard.requireLogin(session);
		String userId = isBlank(request.userId()) ? user.id() : request.userId();
		accessGuard.requireSelfOrAdmin(session, userId);

		Order order = new Order();
		order.setUserId(userId);
		order.setOrderType(isBlank(request.orderType()) ? "TAKEOUT" : request.orderType());
		order.setOrderTable(request.orderTable());
		List<OrderDetail> details = request.details().stream().map(detail -> {
			OrderDetail orderDetail = new OrderDetail();
			orderDetail.setProductId(detail.productId());
			orderDetail.setQuantity(detail.quantity());
			return orderDetail;
		}).toList();

		if (orderService.makeOrder(order, details) <= 0) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Order could not be created");
		}
		OrderInfo created = orderService.getOrderInfo(order.getoId());
		return ResponseEntity.status(HttpStatus.CREATED)
				.body(ApiResponse.message(ResponseMapper.order(created), "created"));
	}

	@GetMapping("/users/{userId}/orders")
	public ResponseEntity<ApiResponse<PageResponse<OrderResponse>>> userOrders(@PathVariable String userId,
			@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
			HttpSession session) {
		accessGuard.requireSelfOrAdmin(session, userId);
		PageResponse<OrderResponse> orders = orderService.getOrdersByUserId(userId, PageCriteria.of(page, size, 10))
				.map(ResponseMapper::order);
		return ResponseEntity.ok(ApiResponse.ok(orders));
	}

	@GetMapping("/orders/{id}")
	public ResponseEntity<ApiResponse<OrderResponse>> order(@PathVariable int id, HttpSession session) {
		OrderInfo order = orderService.getOrderInfo(id);
		if (order == null) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found");
		}
		accessGuard.requireSelfOrAdmin(session, order.getUserId());
		return ResponseEntity.ok(ApiResponse.ok(ResponseMapper.order(order)));
	}

	@GetMapping("/admin/orders")
	public ResponseEntity<ApiResponse<PageResponse<OrderResponse>>> allOrders(@RequestParam(required = false) Integer page,
			@RequestParam(required = false) Integer size, HttpSession session) {
		accessGuard.requireAdmin(session);
		PageResponse<OrderResponse> orders = orderService.getAllOrders(PageCriteria.of(page, size, 20))
				.map(ResponseMapper::order);
		return ResponseEntity.ok(ApiResponse.ok(orders));
	}

	@PatchMapping("/admin/orders/{id}/complete")
	public ResponseEntity<ApiResponse<Boolean>> complete(@PathVariable int id, HttpSession session) {
		accessGuard.requireAdmin(session);
		Order order = orderService.getOrderById(id);
		if (order == null) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found");
		}
		order.setCompleted("Y");
		return ResponseEntity.ok(ApiResponse.ok(orderService.updateOrder(order) > 0));
	}

	@DeleteMapping("/admin/orders/{id}")
	public ResponseEntity<ApiResponse<Boolean>> deleteOrder(@PathVariable int id, HttpSession session) {
		accessGuard.requireAdmin(session);
		if (orderService.removeOrder(id) <= 0) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found");
		}
		return ResponseEntity.ok(ApiResponse.ok(true));
	}

	private boolean isBlank(String value) {
		return value == null || value.trim().isEmpty();
	}
}
