package com.pubburi.pub.controller.api;

public record PageCriteria(int page, int size) {
	private static final int MAX_SIZE = 100;

	public static PageCriteria of(Integer page, Integer size, int defaultSize) {
		int safePage = page == null || page < 1 ? 1 : page;
		int requestedSize = size == null || size < 1 ? defaultSize : size;
		int safeSize = Math.min(requestedSize, MAX_SIZE);
		return new PageCriteria(safePage, safeSize);
	}

	public int offset() {
		long offset = ((long) page - 1) * size;
		if (offset < 0 || offset > Integer.MAX_VALUE) {
			throw new IllegalArgumentException("Page offset exceeds the supported range");
		}
		return (int) offset;
	}
}
