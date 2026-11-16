package com.pubburi.pub.model.service;

import com.pubburi.pub.controller.response.ProductResponse;
import com.pubburi.pub.controller.response.ProductThemesResponse;
import com.pubburi.pub.controller.response.ProductThemesResponse.Item;
import com.pubburi.pub.controller.response.ProductThemesResponse.Source;
import com.pubburi.pub.controller.response.ProductThemesResponse.Theme;
import com.pubburi.pub.controller.response.ProductThemesResponse.ThemeId;
import com.pubburi.pub.model.dao.ProductDao;
import java.util.Arrays;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProductThemeService {
    private final ProductDao products;

    public ProductThemeService(ProductDao products) {
        this.products = products;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ProductThemesResponse list() {
        var themes = Arrays.stream(ThemeId.values()).map(id -> new Theme(id,
            products.selectThemeProducts(id.name(), 6).stream().map(row -> new Item(
                new ProductResponse(row.id(), row.name(), row.type(), row.price(), row.img(), row.abv(), row.orderCount()),
                row.volumeMl())).toList())).toList();
        if (themes.stream().allMatch(theme -> theme.items().isEmpty())) {
            return new ProductThemesResponse(Source.UNAVAILABLE, List.of());
        }
        return new ProductThemesResponse(Source.REVIEWED_LOCAL_CATALOG, themes);
    }
}
