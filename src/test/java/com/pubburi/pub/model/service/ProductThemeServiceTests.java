package com.pubburi.pub.model.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pubburi.pub.controller.response.ProductResponse;
import com.pubburi.pub.controller.response.ProductThemesResponse.Source;
import com.pubburi.pub.controller.response.ProductThemesResponse.Theme;
import com.pubburi.pub.controller.response.ProductThemesResponse.ThemeId;
import com.pubburi.pub.model.service.OwnedContentSnapshotService.Facts;
import com.pubburi.pub.recommendation.ContentCatalogSource;
import com.pubburi.pub.recommendation.ReviewedLocalCatalog;
import com.pubburi.pub.recommendation.ReviewedLocalCatalog.Product;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class ProductThemeServiceTests {
    private static final String NAMESPACE = "12345678-1234-4234-8234-123456789012";
    private final ObjectMapper mapper = new ObjectMapper();
    private final ContentCatalogSource catalogs = mock(ContentCatalogSource.class);
    private final OwnedContentSnapshotService snapshots = mock(OwnedContentSnapshotService.class);
    private final ProductThemeService service = new ProductThemeService(catalogs, snapshots);

    @Test void reviewedBoundariesAndIdTiesAreStableBeforeTheSixItemLimit() throws Exception {
        var records = List.of(product(11, "2", 900), product(10, "8", 500), product(9, "8", 350),
            product(8, "4", 450), product(7, "3", 500), product(6, "2", 700), product(5, "1", 501),
            product(4, "8", 300), product(3, "0", 299), product(2, "7.01", 500), product(1, "7", 500));
        var catalog = new ReviewedLocalCatalog(records, "a".repeat(64), NAMESPACE);
        when(catalogs.current()).thenAnswer(call -> Optional.of(catalog));
        var current = new HashMap<Integer, Facts>();
        for (var record : records) {
            if (record.productId() != 11) current.put(record.productId(), facts(record));
        }
        when(snapshots.readProducts(catalog.ids(), NAMESPACE)).thenReturn(current);

        var response = service.list();
        assertThat(response.source()).isEqualTo(Source.REVIEWED_LOCAL_CATALOG);
        assertThat(response.themes()).extracting(Theme::id).containsExactly(ThemeId.BEGINNER, ThemeId.GIFT, ThemeId.TABLE);
        assertThat(ids(response.themes().get(0))).containsExactly(3, 5, 6, 7, 8, 1);
        assertThat(ids(response.themes().get(1))).containsExactly(4, 9, 8, 1, 2, 7);
        assertThat(ids(response.themes().get(2))).containsExactly(6, 5, 1, 2, 7, 10);
        assertThat(response.themes().get(2).items().get(0).product().price()).isEqualTo(600);
        assertThat(response.themes().get(2).items().get(0).volumeMl()).isEqualTo(700);
        verify(snapshots).readProducts(catalog.ids(), NAMESPACE);
    }

    @Test void emptyCurrentCatalogKeepsAllThemesButAChangedPinFailsClosed() {
        var catalog = new ReviewedLocalCatalog(List.of(product(1, "7", 500)), "a".repeat(64), NAMESPACE);
        when(catalogs.current()).thenAnswer(call -> Optional.of(catalog));
        when(snapshots.readProducts(catalog.ids(), NAMESPACE)).thenReturn(Map.of());
        var response = service.list();
        assertThat(response.source()).isEqualTo(Source.REVIEWED_LOCAL_CATALOG);
        assertThat(response.themes()).extracting(Theme::id).containsExactly(ThemeId.BEGINNER, ThemeId.GIFT, ThemeId.TABLE);
        assertThat(response.themes()).allSatisfy(theme -> assertThat(theme.items()).isEmpty());

        var replaced = new ReviewedLocalCatalog(catalog.records(), "b".repeat(64), NAMESPACE);
        doReturn(Optional.of(catalog), Optional.of(replaced)).when(catalogs).current();
        assertThatThrownBy(service::list).isInstanceOfSatisfying(ResponseStatusException.class,
            error -> assertThat(error.getStatusCode().value()).isEqualTo(409));
    }

    private Product product(int id, String abv, int volume) {
        return new Product(id, "authored-" + id, "Authored " + id, "맥주", new BigDecimal(abv), volume);
    }

    private Facts facts(Product reviewed) throws Exception {
        int id = reviewed.productId();
        var current = new ProductResponse(id, reviewed.label(), reviewed.category(), id * 100, "authored.png",
            reviewed.abvPercent().floatValue(), 0);
        var product = mapper.readTree(mapper.writeValueAsBytes(current));
        var features = mapper.createObjectNode().put("productId", id).put("volumeMl", reviewed.volumeMl());
        return new Facts(product, features, current);
    }

    private List<Integer> ids(Theme theme) {
        return theme.items().stream().map(item -> item.product().id()).toList();
    }
}
