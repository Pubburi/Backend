package com.pubburi.pub.controller.response;

import java.math.BigDecimal;
import java.math.RoundingMode;
import io.swagger.v3.oas.annotations.media.Schema;

public record CommentRatingSummary(int count, @Schema(nullable = true, description = "Whole stored-rating AVG, rounded HALF_UP to two decimals; null only when count is zero") BigDecimal average) {
    public static CommentRatingSummary from(int count, Double average) {
        if (count < 0 || (count == 0) != (average == null) || average != null && !Double.isFinite(average)) {
            throw new IllegalStateException("Invalid review aggregate");
        }
        return new CommentRatingSummary(count, average == null ? null
                : BigDecimal.valueOf(average).setScale(2, RoundingMode.HALF_UP));
    }
}
