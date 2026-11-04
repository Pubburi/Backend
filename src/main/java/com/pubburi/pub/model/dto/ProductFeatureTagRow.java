package com.pubburi.pub.model.dto;

/** Scalar batch projection; never a lazy entity. */
public record ProductFeatureTagRow(int productId, String kind, String tag) { }
