package com.pubburi.pub.controller.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pubburi.pub.controller.auth.AccessGuard;
import com.pubburi.pub.controller.auth.SessionUser;
import com.pubburi.pub.controller.auth.SessionFixtures;
import com.pubburi.pub.controller.response.ResponseMapper;
import com.pubburi.pub.model.dto.OrderDetailInfo;
import com.pubburi.pub.model.dto.OrderInfo;

class OrderHistoryContractTests {
	@Test
	void deletedReferencesSerializeAsNullWhileSnapshotsRemain() {
		OrderDetailInfo line = new OrderDetailInfo();
		line.setName("주문 당시 상품");
		line.setUnitPrice(3000);
		line.setQuantity(2);
		line.setSumPrice(6000);
		OrderInfo order = new OrderInfo();
		order.setTotalPrice(6000);
		order.setDetails(List.of(line));
		JsonNode response = new ObjectMapper().valueToTree(ResponseMapper.order(order));
		assertThat(response.get("userId").isNull()).isTrue();
		assertThat(response.get("details").get(0).get("productId").isNull()).isTrue();
		assertThat(response.get("details").get(0).get("name").asText()).isEqualTo("주문 당시 상품");
		assertThat(response.get("totalPrice").asInt()).isEqualTo(6000);
	}

	@Test
	void nullOwnerCanOnlyBeReadByAnAdministrator() {
		AccessGuard guard = SessionFixtures.guard(SessionFixtures.user("same-id", "USER"),
				SessionFixtures.user("admin", "ADMIN"));
		MockHttpSession formerOrRecreatedUser = session("same-id", "USER");
		assertThatThrownBy(() -> guard.requireSelfOrAdmin(formerOrRecreatedUser, null))
				.isInstanceOfSatisfying(ResponseStatusException.class,
						exception -> assertThat(exception.getStatusCode().value()).isEqualTo(403));
		assertThat(guard.requireSelfOrAdmin(session("admin", "ADMIN"), null).admin()).isTrue();
	}

	private static MockHttpSession session(String id, String role) {
		MockHttpSession session = new MockHttpSession();
		session.setAttribute(AccessGuard.SESSION_USER, SessionUser.from(SessionFixtures.user(id, role)));
		return session;
	}
}
