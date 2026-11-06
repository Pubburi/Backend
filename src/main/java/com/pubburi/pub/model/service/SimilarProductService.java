package com.pubburi.pub.model.service;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.pubburi.pub.controller.response.ContentRecommendationResponse.CatalogScope;
import com.pubburi.pub.controller.response.SimilarProductsResponse;
import com.pubburi.pub.recommendation.ContentCatalogSource;
import com.pubburi.pub.recommendation.SimilarProductContract;
import com.pubburi.pub.recommendation.SimilarProductGateway;

@Service
public class SimilarProductService {
    private static final int LIMIT = 4;
    private static final SimilarProductsResponse EMPTY = new SimilarProductsResponse(List.of());
    private final ProductService products;
    private final OwnedContentSnapshotService snapshots;
    private final ContentCatalogSource catalogs;
    private final SimilarProductGateway provider;

    public SimilarProductService(ProductService products, OwnedContentSnapshotService snapshots,
            ContentCatalogSource catalogs, SimilarProductGateway provider) {
        this.products = products;
        this.snapshots = snapshots;
        this.catalogs = catalogs;
        this.provider = provider;
    }

    public SimilarProductsResponse similar(int referenceId) {
        if (products.getProductById(referenceId) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found");
        }
        var approved = catalogs.current();
        if (approved.isEmpty()) return EMPTY;
        var catalog = approved.get();
        if (catalog.scope() != CatalogScope.REVIEWED_DEMO
                || !SimilarProductContract.hasAvailableReference(catalog.catalog(), referenceId)) return EMPTY;

        // Each projection has its own short read transaction. The provider call is outside both.
        var ids = catalog.ids();
        if (!OwnedContentSnapshotService.matches(catalog, snapshots.readProducts(ids))) return EMPTY;
        var response = provider.similar(catalog.catalog(), referenceId, LIMIT);
        if (response.isEmpty()) return EMPTY;
        List<Integer> candidates;
        try {
            candidates = SimilarProductContract.validate(response.get(), catalog.catalog(), referenceId, LIMIT);
        } catch (IllegalArgumentException invalid) {
            return EMPTY;
        }
        if (candidates.isEmpty()) return EMPTY;

        var current = snapshots.readProducts(ids);
        if (!OwnedContentSnapshotService.matches(catalog, current)) return EMPTY;
        if (catalogs.current().filter(next -> next.sha256().equals(catalog.sha256())).isEmpty()) return EMPTY;
        return new SimilarProductsResponse(candidates.stream().map(id -> current.get(id).currentProduct()).toList());
    }
}
