package com.pubburi.pub.controller.response;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(requiredProperties = {"source", "themes"})
public record ProductThemesResponse(Source source, @ArraySchema(maxItems = 3) List<Theme> themes) {
    public enum Source { REVIEWED_LOCAL_CATALOG, UNAVAILABLE }
    public enum ThemeId { BEGINNER, GIFT, TABLE }

    @Schema(name = "ReviewedProductTheme", requiredProperties = {"id", "items"})
    public record Theme(ThemeId id, @ArraySchema(maxItems = 6) List<Item> items) { }

    @Schema(name = "ReviewedProductThemeItem", requiredProperties = {"product", "volumeMl"})
    public record Item(ProductResponse product, @Schema(minimum = "1", maximum = "1000000") int volumeMl) { }
}
