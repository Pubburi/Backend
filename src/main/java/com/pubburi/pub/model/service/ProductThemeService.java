package com.pubburi.pub.model.service;

import static com.pubburi.pub.controller.response.ProductThemesResponse.ThemeId.*;

import com.pubburi.pub.controller.response.ProductThemesResponse;
import com.pubburi.pub.controller.response.ProductThemesResponse.Item;
import com.pubburi.pub.controller.response.ProductThemesResponse.Source;
import com.pubburi.pub.controller.response.ProductThemesResponse.Theme;
import com.pubburi.pub.controller.response.ProductThemesResponse.ThemeId;
import com.pubburi.pub.model.service.OwnedContentSnapshotService.Facts;
import com.pubburi.pub.recommendation.ContentCatalogSource;
import com.pubburi.pub.recommendation.ReviewedLocalCatalog;
import com.pubburi.pub.recommendation.ReviewedLocalCatalog.Product;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ProductThemeService {
    private static final int THEME_LIMIT = 6;
    private static final BigDecimal BEGINNER_MAX_ABV = BigDecimal.valueOf(7);
    private final ContentCatalogSource catalogs;
    private final OwnedContentSnapshotService snapshots;

    public ProductThemeService(ContentCatalogSource catalogs, OwnedContentSnapshotService snapshots) {
        this.catalogs = catalogs;
        this.snapshots = snapshots;
    }

    public ProductThemesResponse list() {
        var configured = catalogs.current();
        if (configured.isEmpty() || !(configured.get() instanceof ReviewedLocalCatalog authority)) {
            return new ProductThemesResponse(Source.UNAVAILABLE, List.of());
        }

        var current = snapshots.readProducts(authority.ids(), authority.businessSourceId());
        if (authority.resolve(current).isEmpty() || catalogs.current()
                .filter(ReviewedLocalCatalog.class::isInstance)
                .filter(next -> authority.sha256().equals(next.sha256())
                    && authority.businessSourceId().equals(next.businessSourceId())).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Reviewed theme catalog facts changed");
        }

        var available = authority.records().stream().filter(product -> current.containsKey(product.productId())).toList();
        var byVolume = Comparator.comparingInt(Product::volumeMl);
        return new ProductThemesResponse(Source.REVIEWED_LOCAL_CATALOG, List.of(
            theme(BEGINNER, available.stream().filter(product -> product.abvPercent().compareTo(BEGINNER_MAX_ABV) <= 0),
                Comparator.comparing(Product::abvPercent), current),
            theme(GIFT, available.stream().filter(product -> product.volumeMl() >= 300 && product.volumeMl() <= 500),
                byVolume, current),
            theme(TABLE, available.stream().filter(product -> product.volumeMl() >= 500), byVolume.reversed(), current)));
    }

    private Theme theme(ThemeId id, Stream<Product> products, Comparator<Product> order, Map<Integer, Facts> current) {
        var items = products.sorted(order.thenComparingInt(Product::productId)).limit(THEME_LIMIT)
            .map(product -> new Item(current.get(product.productId()).currentProduct(), product.volumeMl())).toList();
        return new Theme(id, items);
    }
}
