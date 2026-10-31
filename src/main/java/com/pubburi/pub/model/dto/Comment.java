package com.pubburi.pub.model.dto;

public class Comment {
    private int id;
    private String userId;
    private int productId;
    private float rating;
    private String comment;

	public Comment(int id, String userId, int productId, float rating, String comment) {
		super();
		this.id = id;
		this.userId = userId;
		this.productId = productId;
		this.rating = rating;
		this.comment = comment;
	}

    public Comment() {}

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

	public int getProductId() {
		return productId;
	}

	public void setProductId(int productId) {
		this.productId = productId;
	}

	public float getRating() {
		return rating;
	}

	public void setRating(float rating) {
		this.rating = rating;
	}

	public String getComment() {
		return comment;
	}

	public void setComment(String comment) {
		this.comment = comment;
	}

	@Override
	public String toString() {
		return "Comment [id=" + id + ", userId=" + userId + ", productId=" + productId + ", rating=" + rating
				+ ", comment=" + comment + "]";
	}

}
