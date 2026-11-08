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
        var authority = approved.get();
        if (authority.scope() == CatalogScope.REVIEWED_FAMILY_BROWSE_DEMO) return EMPTY;

        // Each projection has its own short read transaction. The provider call is outside both.
        var ids = authority.ids();var namespace=authority.businessSourceId();
        var resolved=authority.resolve(read(ids,namespace));
        if(resolved.isEmpty()){if(authority.scope()==CatalogScope.REVIEWED_LOCAL_CATALOG)changed();return EMPTY;}
        var catalog=resolved.get();
        if(!SimilarProductContract.hasAvailableReference(catalog.catalog(),referenceId))return EMPTY;
        var response = provider.similar(catalog.catalog(), referenceId, LIMIT);
        if(response.isEmpty() && catalog.scope()!=CatalogScope.REVIEWED_LOCAL_CATALOG)return EMPTY;
        List<Integer> candidates=List.of();
        if(response.isPresent())try{
            candidates=SimilarProductContract.validate(response.get(),catalog.catalog(),referenceId,LIMIT);
        }catch(IllegalArgumentException invalid){if(catalog.scope()!=CatalogScope.REVIEWED_LOCAL_CATALOG)return EMPTY;}
        if(candidates.isEmpty() && catalog.scope()!=CatalogScope.REVIEWED_LOCAL_CATALOG)return EMPTY;
        var current=read(ids,namespace);
        if(!OwnedContentSnapshotService.matches(catalog,current)){
            if(catalog.scope()==CatalogScope.REVIEWED_LOCAL_CATALOG)changed();return EMPTY;
        }
        if(catalogs.current().filter(next->next.sha256().equals(authority.sha256())).isEmpty()){
            if(catalog.scope()==CatalogScope.REVIEWED_LOCAL_CATALOG)changed();return EMPTY;
        }
        if(response.isEmpty())return result(List.of(),catalog.scope());
        return result(candidates.stream().map(id -> current.get(id).currentProduct()).toList(),catalog.scope());
    }
    private java.util.Map<Integer,OwnedContentSnapshotService.Facts> read(List<Integer> ids,String namespace){
        return namespace==null?snapshots.readProducts(ids):snapshots.readProducts(ids,namespace);
    }
    private SimilarProductsResponse result(List<com.pubburi.pub.controller.response.ProductResponse> items,CatalogScope scope){
        return scope==CatalogScope.REVIEWED_LOCAL_CATALOG?new SimilarProductsResponse(items,scope):new SimilarProductsResponse(items);
    }
    private void changed(){throw new ResponseStatusException(HttpStatus.CONFLICT,"Reviewed recommendation inputs changed");}
}
