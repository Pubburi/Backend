package com.pubburi.pub.model.dto;

public record ProductThemeRow(int id, String name, String type, int price, String img, float abv,
                              int orderCount, int volumeMl) { }
