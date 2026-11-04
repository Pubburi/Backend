package com.pubburi.pub.controller.response;

import java.util.List;
import java.util.function.Function;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.pubburi.pub.controller.api.PageResponse;

public record CommentPageResponse<T>(List<T> items, int page, int size, int total, int totalPages,
        boolean hasNext, @JsonInclude(JsonInclude.Include.NON_NULL) CommentRatingSummary ratingSummary) {
    public static <T> CommentPageResponse<T> from(PageResponse<T> page, CommentRatingSummary summary) {
        return new CommentPageResponse<>(page.items(), page.page(), page.size(), page.total(),
                page.totalPages(), page.hasNext(), summary);
    }

    public <R> CommentPageResponse<R> map(Function<T, R> mapper) {
        return new CommentPageResponse<>(items.stream().map(mapper).toList(), page, size, total,
                totalPages, hasNext, ratingSummary);
    }
}
