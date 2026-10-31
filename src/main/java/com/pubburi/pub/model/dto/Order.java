package com.pubburi.pub.model.dto;

import java.sql.Timestamp;
import java.util.List;

public class Order {
	private int oId;
	private String userId;
	private String orderType;
	private String orderTable;
	private String completed;
	private Timestamp orderTime;

	// ✅ 주문 상세 리스트 필드 추가
	private List<OrderDetail> details;

	// 기본 생성자
	public Order() {
	}

	// Getter/Setter
	public int getoId() {
		return oId;
	}

	public void setoId(int oId) {
		this.oId = oId;
	}

	public String getUserId() {
		return userId;
	}

	public void setUserId(String userId) {
		this.userId = userId;
	}

	public String getOrderType() {
		return orderType;
	}

	public void setOrderType(String orderType) {
		this.orderType = orderType;
	}

	public String getOrderTable() {
		return orderTable;
	}

	public void setOrderTable(String orderTable) {
		this.orderTable = orderTable;
	}

	public String getCompleted() {
		return completed;
	}

	public void setCompleted(String completed) {
		this.completed = completed;
	}

	public Timestamp getOrderTime() {
		return orderTime;
	}

	public void setOrderTime(Timestamp orderTime) {
		this.orderTime = orderTime;
	}

	public List<OrderDetail> getDetails() {
		return details;
	}

	public void setDetails(List<OrderDetail> details) {
		this.details = details;
	}
}
