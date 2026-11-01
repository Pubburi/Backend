package com.pubburi.pub.model.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

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
import com.pubburi.pub.model.dto.Product;
import com.pubburi.pub.model.dto.Stamp;
import com.pubburi.pub.model.dto.User;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class OrderServiceImplTests {
	private final FakeOrderDao orderDao = new FakeOrderDao();
	private final FakeOrderDetailDao orderDetailDao = new FakeOrderDetailDao();
	private final FakeStampDao stampDao = new FakeStampDao();
	private final FakeUserDao userDao = new FakeUserDao();
	private final FakeProductDao productDao = new FakeProductDao();
	private final ProductSearchOutbox searchOutbox = org.mockito.Mockito.mock(ProductSearchOutbox.class);
	private final OrderServiceImpl orderService = new OrderServiceImpl(orderDao, orderDetailDao, stampDao, userDao,
			productDao, searchOutbox);

	@Test
	void makeOrderCapturesServerPriceRatherThanMutableCatalogOrClientValues() {
		Order order = new Order();
		order.setUserId("id01");
		orderService.makeOrder(order, List.of(detail(1, 2)));
		JsonNode saved = new ObjectMapper().valueToTree(orderDetailDao.insertedDetails.get(0));
		assertThat(saved.path("unitPrice").asInt()).isEqualTo(1000);
		assertThat(saved.path("sumPrice").asInt()).isEqualTo(2000);
		assertThat(saved.path("productName").asText()).isEqualTo("상품 1");
		assertThat(order.getTotalPrice()).isEqualTo(2000);
		assertThat(order.getPriceBasis()).isEqualTo("ORDER_SNAPSHOT");
		org.mockito.Mockito.verify(searchOutbox).recordCurrent(1);
		productDao.price = 5000;
		assertThat(orderDetailDao.insertedDetails.get(0).getUnitPrice()).isEqualTo(1000);
		assertThat(order.getTotalPrice()).isEqualTo(2000);
	}

	@Test
	void clientSnapshotsAreDiscardedAndServerTotalsAreCaptured() {
		Order order = new Order();
		order.setUserId("id01");
		order.setTotalPrice(1);
		OrderDetail item = detail(1, 2);
		item.setUnitPrice(1);
		item.setProductName("client supplied");
		orderService.makeOrder(order, List.of(item));
		assertThat(orderDetailDao.insertedDetails.get(0).getProductName()).isEqualTo("상품 1");
		assertThat(orderDetailDao.insertedDetails.get(0).getSumPrice()).isEqualTo(2000);
		assertThat(orderDao.savedTotal).isEqualTo(2000);
	}

	@Test
	void outOfRangeLineAmountStopsBeforeDetailsAndRewards() {
		Order order = new Order();
		order.setUserId("id01");
		productDao.price = Integer.MAX_VALUE;
		assertThatThrownBy(() -> orderService.makeOrder(order, List.of(detail(1, 2))))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("out of range");
		assertThat(orderDetailDao.insertedDetails).isEmpty();
		assertThat(stampDao.saved).isNull();
		assertThat(productDao.orderCountIncrements).isEmpty();
		org.mockito.Mockito.verifyNoInteractions(searchOutbox);
	}

	@Test
	void outOfRangeOrderTotalStopsBeforeDetailsAndRewards() {
		Order order = new Order();
		order.setUserId("id01");
		productDao.price = 1_500_000_000;
		assertThatThrownBy(() -> orderService.makeOrder(order, List.of(detail(1, 1), detail(2, 1))))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("out of range");
		assertThat(orderDetailDao.insertedDetails).isEmpty();
		assertThat(stampDao.saved).isNull();
	}

	@Test
	void makeOrderAggregatesDuplicateProductsAndIncrementsStampsAtomically() {
		Order order = new Order();
		order.setUserId("id01");

		int result = orderService.makeOrder(order,
				List.of(detail(1, 2), detail(1, 3), detail(2, 1)));

		assertThat(result).isEqualTo(1);
		assertThat(order.getoId()).isEqualTo(100);
		assertThat(order.getCompleted()).isEqualTo("N");
		assertThat(orderDetailDao.insertedDetails).hasSize(2);
		assertThat(orderDetailDao.insertedDetails)
				.extracting(OrderDetail::getProductId, OrderDetail::getQuantity, OrderDetail::getOrderId)
				.containsExactlyInAnyOrder(
						org.assertj.core.groups.Tuple.tuple(1, 5, 100),
						org.assertj.core.groups.Tuple.tuple(2, 1, 100));
		assertThat(productDao.orderCountIncrements).containsEntry(1, 5).containsEntry(2, 1);
		assertThat(stampDao.saved.getQuantity()).isEqualTo(6);
		assertThat(userDao.incrementedId).isEqualTo("id01");
		assertThat(userDao.incrementedQuantity).isEqualTo(6);
		assertThat(order.getTotalPrice()).isEqualTo(6000);
	}

	@Test
	void pagedOrdersAttachDetailsWithOneBatchQuery() {
		orderDao.orders = List.of(orderInfo(10), orderInfo(20));
		orderDao.details = List.of(detailInfo(10, 1), detailInfo(20, 2), detailInfo(20, 3));

		PageResponse<OrderInfo> page = orderService.getAllOrders(PageCriteria.of(1, 20, 20));

		assertThat(page.items()).hasSize(2);
		assertThat(orderDao.batchDetailCalls).isEqualTo(1);
		assertThat(orderDao.lastDetailOrderIds).containsExactly(10, 20);
		assertThat(page.items().get(0).getDetails()).extracting(OrderDetailInfo::getId).containsExactly(1);
		assertThat(page.items().get(1).getDetails()).extracting(OrderDetailInfo::getId).containsExactly(2, 3);
	}

	@Test
	void emptyOrderPagesSkipDetailBatchQuery() {
		orderDao.orders = List.of();

		PageResponse<OrderInfo> page = orderService.getAllOrders(PageCriteria.of(1, 20, 20));

		assertThat(page.items()).isEmpty();
		assertThat(orderDao.batchDetailCalls).isZero();
	}

	@Test
	void missingProductStopsOrderBeforeDetailsAndRewardsAreCreated() {
		Order order = new Order();
		order.setUserId("id01");
		productDao.missingProductIds = Set.of(99);

		assertThatThrownBy(() -> orderService.makeOrder(order, List.of(detail(99, 1))))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("Product not found");
		assertThat(orderDetailDao.insertedDetails).isEmpty();
		assertThat(stampDao.saved).isNull();
		assertThat(userDao.incrementedId).isNull();
	}

	private static OrderDetail detail(int productId, int quantity) {
		OrderDetail detail = new OrderDetail();
		detail.setProductId(productId);
		detail.setQuantity(quantity);
		return detail;
	}

	private static OrderInfo orderInfo(int id) {
		OrderInfo order = new OrderInfo();
		order.setoId(id);
		order.setUserId("id01");
		return order;
	}

	private static OrderDetailInfo detailInfo(int orderId, int id) {
		OrderDetailInfo detail = new OrderDetailInfo();
		detail.setId(id);
		detail.setOrderId(orderId);
		return detail;
	}

	private static class FakeOrderDao implements OrderDao {
		private List<OrderInfo> orders = new ArrayList<>();
		private List<OrderDetailInfo> details = new ArrayList<>();
		private int batchDetailCalls;
		private List<Integer> lastDetailOrderIds = List.of();
		private int savedTotal;

		@Override
		public int updateTotalPrice(int orderId, int totalPrice) {
			savedTotal = totalPrice;
			return 1;
		}

		@Override
		public List<Order> selectByUserId(String userId) {
			return List.of();
		}

		@Override
		public Order selectById(int oId) {
			return null;
		}

		@Override
		public int insert(Order order) {
			order.setoId(100);
			return 1;
		}

		@Override
		public int update(Order order) {
			return 1;
		}

		@Override
		public int delete(int oId) {
			return 1;
		}

		@Override
		public OrderInfo selectOrderInfo(int oId) {
			return orders.stream().filter((order) -> order.getoId() == oId).findFirst().orElse(null);
		}

		@Override
		public List<OrderDetailInfo> getOrderDetailInfo(int orderId) {
			return details.stream().filter((detail) -> detail.getOrderId() == orderId).toList();
		}

		@Override
		public List<OrderDetailInfo> getOrderDetailInfoByOrderIds(List<Integer> orderIds) {
			batchDetailCalls++;
			lastDetailOrderIds = List.copyOf(orderIds);
			return details.stream().filter((detail) -> orderIds.contains(detail.getOrderId())).toList();
		}

		@Override
		public List<OrderInfo> selectOrderInfoListByUserId(String userId) {
			return new ArrayList<>(orders);
		}

		@Override
		public List<OrderInfo> selectOrderInfoPageByUserId(String userId, int limit, int offset) {
			return new ArrayList<>(orders);
		}

		@Override
		public int countOrderInfoByUserId(String userId) {
			return orders.size();
		}

		@Override
		public List<OrderInfo> selectAllOrderInfo() {
			return new ArrayList<>(orders);
		}

		@Override
		public List<OrderInfo> selectAllOrderInfoPaged(int limit, int offset) {
			return new ArrayList<>(orders);
		}

		@Override
		public int countAllOrderInfo() {
			return orders.size();
		}

		@Override
		public List<OrderInfo> getLastMonthOrder(String id) {
			return new ArrayList<>(orders);
		}

		@Override
		public List<OrderInfo> getLast6MonthOrder(String id) {
			return new ArrayList<>(orders);
		}
	}

	private static class FakeOrderDetailDao implements OrderDetailDao {
		private List<OrderDetail> insertedDetails = List.of();

		@Override
		public List<OrderDetail> selectByOrderId(int orderId) {
			return List.of();
		}

		@Override
		public int insert(OrderDetail orderDetail) {
			insertedDetails = List.of(orderDetail);
			return 1;
		}

		@Override
		public int insertAll(List<OrderDetail> details) {
			insertedDetails = new ArrayList<>(details);
			return details.size();
		}

		@Override
		public int deleteByOrderId(int orderId) {
			return 1;
		}
	}

	private static class FakeStampDao implements StampDao {
		private Stamp saved;

		@Override
		public List<Stamp> selectByUserId(String userId) {
			return List.of();
		}

		@Override
		public int insert(Stamp stamp) {
			saved = stamp;
			return 1;
		}
	}

	private static class FakeUserDao implements UserDao {
		private String incrementedId;
		private int incrementedQuantity;

		@Override
		public int clearOrderNotes(String id) { return 0; }

		@Override
		public User selectByIdForUpdate(String userId) { return selectById(userId); }

		@Override
		public User selectSessionIdentity(String id, String authEpoch) { return null; }

		@Override
		public List<User> selectAll() {
			return List.of();
		}

		@Override
		public List<User> selectPaged(int limit, int offset) {
			return List.of();
		}

		@Override
		public int countAll() {
			return 0;
		}

		@Override
		public int insert(User user) {
			return 1;
		}

		@Override
		public int update(User user) {
			return 1;
		}

		@Override
		public int upgradePassword(String id, String expectedHash, String authEpoch, String pass) {
			return 1;
		}

		@Override
		public int delete(String id) {
			return 1;
		}

		@Override
		public int updateStamp(User user) {
			return 1;
		}

		@Override
		public int incrementStamps(String id, int quantity) {
			incrementedId = id;
			incrementedQuantity = quantity;
			return 1;
		}

		@Override
		public User selectById(String userId) {
			return null;
		}
	}

	private static class FakeProductDao implements ProductDao {
		@Override public List<Product> selectDefaultCandidates(int limit) { throw new UnsupportedOperationException(); }
		@Override public List<Product> selectCurrentByIds(List<Integer> ids) { throw new UnsupportedOperationException(); }
		private Map<Integer, Integer> orderCountIncrements = new LinkedHashMap<>();
		private Set<Integer> missingProductIds = Set.of();
		private int price = 1000;

		@Override
		public List<Product> selectAll() {
			return List.of();
		}

		@Override
		public List<Product> selectFiltered(String type, String q, String sort, com.pubburi.pub.controller.api.ProductSearchFilters filters, int limit, int offset) {
			return List.of();
		}

		@Override
		public int countFiltered(String type, String q, com.pubburi.pub.controller.api.ProductSearchFilters filters) {
			return 0;
		}

		@Override
		public Product selectById(int id) {
			return missingProductIds.contains(id) ? null : new Product(id, "상품 " + id, "탁주", price, "test.png", 6);
		}

		@Override
		public Product selectByIdForUpdate(int id) {
			return selectById(id);
		}

		@Override
		public List<Product> selectPopular() {
			return List.of();
		}

		@Override
		public int insert(Product product) {
			return 1;
		}

		@Override
		public int update(Product product) {
			return 1;
		}

		@Override
		public int delete(int id) {
			return 1;
		}

		@Override
		public int incrementOrderCount(int productId, int quantity) {
			if (missingProductIds.contains(productId)) {
				return 0;
			}
			orderCountIncrements.merge(productId, quantity, Integer::sum);
			return 1;
		}

		@Override
		public Product selectByExactName(String name) {
			return null;
		}

		@Override
		public List<Product> selectTopNByTypeExcludeName(String type, String excludeName, int n) {
			return List.of();
		}
	}
}
