package com.pubburi.pub.model.dto;

public class OrderDetail {
    private int dId;
    private int orderId;
    private int productId;
    private int quantity;

	public OrderDetail(int dId, int orderId, int productId, int quantity) {
		super();
		this.dId = dId;
		this.orderId = orderId;
		this.productId = productId;
		this.quantity = quantity;
	}

    public OrderDetail() {}

	public int getdId() {
		return dId;
	}

	public void setdId(int dId) {
		this.dId = dId;
	}

	public int getOrderId() {
		return orderId;
	}

	public void setOrderId(int orderId) {
		this.orderId = orderId;
	}

	public int getProductId() {
		return productId;
	}

	public void setProductId(int productId) {
		this.productId = productId;
	}

	public int getQuantity() {
		return quantity;
	}

	public void setQuantity(int quantity) {
		this.quantity = quantity;
	}

	@Override
	public String toString() {
		return "OrderDetail [dId=" + dId + ", orderId=" + orderId + ", productId=" + productId + ", quantity="
				+ quantity + "]";
	}

}
