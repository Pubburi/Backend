package com.pubburi.pub.model.dto;

public class Stamp {
    private int id;
    private String userId;
    private int orderId;
    private int quantity;

	public Stamp(int id, String userId, int orderId, int quantity) {
		super();
		this.id = id;
		this.userId = userId;
		this.orderId = orderId;
		this.quantity = quantity;
	}

	public Stamp() {}

	public int getId() {
		return id;
	}

	public void setId(int id) {
		this.id = id;
	}

	public String getUserId() {
		return userId;
	}

	public void setUserId(String userId) {
		this.userId = userId;
	}

	public int getOrderId() {
		return orderId;
	}

	public void setOrderId(int orderId) {
		this.orderId = orderId;
	}

	public int getQuantity() {
		return quantity;
	}

	public void setQuantity(int quantity) {
		this.quantity = quantity;
	}

	@Override
	public String toString() {
		return "Stamp [id=" + id + ", userId=" + userId + ", orderId=" + orderId + ", quantity=" + quantity + "]";
	}

}
