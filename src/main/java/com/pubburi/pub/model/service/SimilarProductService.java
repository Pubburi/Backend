package com.pubburi.pub.model.service;

import java.util.List;
import com.fasterxml.jackson.databind.ObjectMapper;

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
    private final ObjectMapper mapper;

    public SimilarProductService(ProductService products, OwnedContentSnapshotService snapshots,
            ContentCatalogSource catalogs, SimilarProductGateway provider,ObjectMapper mapper) {
        this.products = products;
        this.snapshots = snapshots;
        this.catalogs = catalogs;
        this.provider = provider;
        this.mapper=mapper;
    }

    public SimilarProductsResponse similar(int referenceId) {
        return similar(referenceId,false);
    }
    public SimilarProductsResponse similar(int referenceId,boolean explained) {
        if (products.getProductById(referenceId) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found");
        }
        var approved = catalogs.current();
        if (approved.isEmpty()) return empty(explained);
        var authority = approved.get();
        if (authority.scope() == CatalogScope.REVIEWED_FAMILY_BROWSE_DEMO) return empty(explained);

        // Each projection has its own short read transaction. The provider call is outside both.
        var ids = authority.ids();var namespace=authority.businessSourceId();
        var resolved=authority.resolve(read(ids,namespace));
        if(resolved.isEmpty()){if(authority.scope()==CatalogScope.REVIEWED_LOCAL_CATALOG)changed();return empty(explained);}
        var catalog=resolved.get();
        if(!SimilarProductContract.hasAvailableReference(catalog.catalog(),referenceId))return empty(explained);
        var response = explained?provider.similarExplained(catalog.catalog(),referenceId,LIMIT):provider.similar(catalog.catalog(), referenceId, LIMIT);
        if(response.isEmpty() && catalog.scope()!=CatalogScope.REVIEWED_LOCAL_CATALOG)return empty(explained);
        List<Integer> candidates=List.of();
        List<SimilarProductsResponse.Explanation> explanations=explained?List.of():null;
        if(response.isPresent())try{
            if(explained){explanations=SimilarProductContract.validateExplained(response.get(),catalog.catalog(),referenceId,LIMIT,mapper);candidates=explanations.stream().map(SimilarProductsResponse.Explanation::productId).toList();}
            else candidates=SimilarProductContract.validate(response.get(),catalog.catalog(),referenceId,LIMIT);
        }catch(IllegalArgumentException invalid){if(catalog.scope()!=CatalogScope.REVIEWED_LOCAL_CATALOG)return empty(explained);}
        if(candidates.isEmpty() && catalog.scope()!=CatalogScope.REVIEWED_LOCAL_CATALOG)return empty(explained);
        var current=read(ids,namespace);
        if(!OwnedContentSnapshotService.matches(catalog,current)){
            if(catalog.scope()==CatalogScope.REVIEWED_LOCAL_CATALOG)changed();return empty(explained);
        }
        if(catalogs.current().filter(next->next.sha256().equals(authority.sha256())).isEmpty()){
            if(catalog.scope()==CatalogScope.REVIEWED_LOCAL_CATALOG)changed();return empty(explained);
        }
        return new SimilarProductsResponse(candidates.stream().map(id -> current.get(id).currentProduct()).toList(),
            catalog.scope()==CatalogScope.REVIEWED_LOCAL_CATALOG?catalog.scope():null,explanations);
    }
    private java.util.Map<Integer,OwnedContentSnapshotService.Facts> read(List<Integer> ids,String namespace){
        return namespace==null?snapshots.readProducts(ids):snapshots.readProducts(ids,namespace);
    }
    private SimilarProductsResponse empty(boolean explained){return explained?new SimilarProductsResponse(List.of(),null,List.of()):EMPTY;}
    private void changed(){throw new ResponseStatusException(HttpStatus.CONFLICT,"Reviewed recommendation inputs changed");}
}
