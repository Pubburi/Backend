package com.pubburi.pub.controller.response;

import java.util.List;

import com.pubburi.pub.model.dto.Comment;
import com.pubburi.pub.model.dto.Market;
import com.pubburi.pub.model.dto.OrderDetailInfo;
import com.pubburi.pub.model.dto.OrderInfo;
import com.pubburi.pub.model.dto.Product;
import com.pubburi.pub.model.dto.User;

public final class ResponseMapper {
	private ResponseMapper() {
	}

	public static UserResponse user(User user) {
		return new UserResponse(user.getId(), user.getName(), user.getStamps(), user.getRole(), user.isAdmin());
	}

	public static ProductResponse product(Product product) {
		return new ProductResponse(product.getId(), product.getName(), product.getType(), product.getPrice(),
				product.getImg(), product.getAbv(), product.getOrderCount());
	}

	public static MarketResponse market(Market market) {
		return new MarketResponse(market.getId(), market.getName(), market.getLatitude(), market.getLongitude(),
				market.getImage());
	}

	public static CommentResponse comment(Comment comment) {
		return new CommentResponse(comment.getId(), comment.getUserId(), comment.getProductId(), comment.getRating(),
				comment.getComment());
	}

	public static OrderResponse order(OrderInfo order) {
		List<OrderDetailResponse> details = order.getDetails() == null ? List.of()
				: order.getDetails().stream().map(ResponseMapper::orderDetail).toList();
		return new OrderResponse(order.getoId(), order.getUserId(), order.getOrderType(), order.getOrderTable(),
				order.getCompleted(), order.getOrderTime(), details);
	}

	private static OrderDetailResponse orderDetail(OrderDetailInfo detail) {
		return new OrderDetailResponse(detail.getId(), detail.getOrderId(), detail.getProductId(),
				detail.getQuantity(), detail.getName(), detail.getUnitPrice(), detail.getSumPrice(), detail.getType(),
				detail.getImg());
	}
}
