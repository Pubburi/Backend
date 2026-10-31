package com.pubburi.pub.controller.response;

public record ProductResponse(int id, String name, String type, int price, String img, float abv, int orderCount) {
}
