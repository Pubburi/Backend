package com.pubburi.pub.controller.api;

import java.util.List;
import java.util.function.Function;

public record PageResponse<T>(List<T> items, int page, int size, int total, int totalPages, boolean hasNext) {
	public static <T> PageResponse<T> of(List<T> items, PageCriteria criteria, int total) {
		int totalPages = total == 0 ? 0 : (int) Math.ceil((double) total / criteria.size());
		boolean hasNext = criteria.page() < totalPages;
		return new PageResponse<>(items, criteria.page(), criteria.size(), total, totalPages, hasNext);
	}

	public <R> PageResponse<R> map(Function<T, R> mapper) {
		return new PageResponse<>(items.stream().map(mapper).toList(), page, size, total, totalPages, hasNext);
	}
}
