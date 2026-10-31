package com.pubburi.pub.model.dto;

public class Product {
	private int id;
	private String name;
	private String type;
	private int price;
	private String img;
	private float abv;
	private int orderCount; // 누적 주문 수

	public Product(int id, String name, String type, int price, String img, float abv) {
		this(id, name, type, price, img, abv, 0);
	}

	public Product(int id, String name, String type, int price, String img, float abv, int orderCount) {
		super();
		this.id = id;
		this.name = name;
		this.type = type;
		this.price = price;
		this.img = img;
		this.abv = abv;
		this.orderCount = orderCount;
	}

	public Product() {
	}

	public int getId() {
		return id;
	}

	public void setId(int id) {
		this.id = id;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getType() {
		return type;
	}

	public void setType(String type) {
		this.type = type;
	}

	public int getPrice() {
		return price;
	}

	public void setPrice(int price) {
		this.price = price;
	}

	public String getImg() {
		return img;
	}

	public void setImg(String img) {
		this.img = img;
	}

	public float getAbv() {
		return abv;
	}

	public void setAbv(float abv) {
		this.abv = abv;
	}

	public int getOrderCount() {
		return orderCount;
	}

	public void setOrderCount(int orderCount) {
		this.orderCount = orderCount;
	}

	@Override
	public String toString() {
		return "Product [id=" + id + ", name=" + name + ", type=" + type + ", price=" + price + ", img=" + img
				+ ", abv=" + abv + ", orderCount=" + orderCount + "]";
	}
}
