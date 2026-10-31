package com.pubburi.pub.model.dto;

public class Market {
	private int id; // 마켓 고유 ID
	private double latitude; // 위도
	private double longitude; // 경도
	private String image; // 매장 이미지 URL 또는 파일명
	private String name; // 매장 이름

	// 기본 생성자
	public Market() {
	}

	// 전체 필드 생성자
	public Market(int id, double latitude, double longitude, String image, String name) {
		this.id = id;
		this.latitude = latitude;
		this.longitude = longitude;
		this.image = image;
		this.name = name;
	}

	// Getter & Setter
	public int getId() {
		return id;
	}

	public void setId(int id) {
		this.id = id;
	}

	public double getLatitude() {
		return latitude;
	}

	public void setLatitude(double latitude) {
		this.latitude = latitude;
	}

	public double getLongitude() {
		return longitude;
	}

	public void setLongitude(double longitude) {
		this.longitude = longitude;
	}

	public String getImage() {
		return image;
	}

	public void setImage(String image) {
		this.image = image;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}
}
