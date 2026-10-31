package com.pubburi.pub.model.dto;

public class OrderDetail {
    private int dId;
    private int orderId;
    private int productId;
    private int quantity;
    private String productName;
    private String productType;
    private String productImg;
    private int unitPrice;
    private int sumPrice;

    public String getProductName() { return productName; }
    public void setProductName(String productName) { this.productName = productName; }
    public String getProductType() { return productType; }
    public void setProductType(String productType) { this.productType = productType; }
    public String getProductImg() { return productImg; }
    public void setProductImg(String productImg) { this.productImg = productImg; }
    public int getUnitPrice() { return unitPrice; }
    public void setUnitPrice(int unitPrice) { this.unitPrice = unitPrice; }
    public int getSumPrice() { return sumPrice; }
    public void setSumPrice(int sumPrice) { this.sumPrice = sumPrice; }

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
