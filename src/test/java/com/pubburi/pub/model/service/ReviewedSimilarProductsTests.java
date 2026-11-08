package com.pubburi.pub.model.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import com.pubburi.pub.model.dto.Product;
import com.pubburi.pub.recommendation.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class ReviewedSimilarProductsTests {
    @Test void onlyCheckedCurrentReferencesReceiveLocalMetadataAndFailuresStillRecheckPrices()throws Exception{
        var authority=ReviewedLocalFixtures.catalog();var snapshots=mock(OwnedContentSnapshotService.class);var products=mock(ProductService.class);var provider=mock(SimilarProductGateway.class);
        when(products.getProductById(anyInt())).thenReturn(new Product());var current=ReviewedLocalFixtures.current();when(snapshots.readProducts(authority.ids(),authority.businessSourceId())).thenReturn(current);when(provider.similar(any(),eq(81),eq(4))).thenReturn(Optional.empty());
        var service=new SimilarProductService(products,snapshots,()->Optional.of(authority),provider,ReviewedLocalFixtures.JSON);
        var result=service.similar(81);assertEquals("REVIEWED_LOCAL_CATALOG",result.catalogScope().name());assertTrue(result.items().isEmpty());
        var unmapped=service.similar(99);assertNull(unmapped.catalogScope());assertFalse(ReviewedLocalFixtures.JSON.valueToTree(unmapped).has("catalogScope"));verify(provider,times(1)).similar(any(),eq(81),eq(4));
        var changed=ReviewedLocalFixtures.current();ReviewedLocalFixtures.price(changed,81,9000);when(snapshots.readProducts(authority.ids(),authority.businessSourceId())).thenReturn(current,changed);
        assertEquals(409,assertThrows(ResponseStatusException.class,()->service.similar(81)).getStatusCode().value());
    }
    @Test void optInEmptyResultsRetainTheLocalScopeAndNeverInventReasonsOrSkipCurrentFactChecks()throws Exception{
        var authority=ReviewedLocalFixtures.catalog();var snapshots=mock(OwnedContentSnapshotService.class);var products=mock(ProductService.class);var provider=mock(SimilarProductGateway.class);
        when(products.getProductById(anyInt())).thenReturn(new Product());var current=ReviewedLocalFixtures.current();when(snapshots.readProducts(authority.ids(),authority.businessSourceId())).thenReturn(current);
        when(provider.similarExplained(any(),eq(81),eq(4))).thenReturn(Optional.empty());
        var service=new SimilarProductService(products,snapshots,()->Optional.of(authority),provider,ReviewedLocalFixtures.JSON);
        var empty=service.similar(81,true);assertTrue(empty.items().isEmpty());assertTrue(empty.explanations().isEmpty());assertEquals("REVIEWED_LOCAL_CATALOG",empty.catalogScope().name());
        assertTrue(service.similar(99,true).explanations().isEmpty());verify(provider,never()).similar(any(),anyInt(),anyInt());
        var changed=ReviewedLocalFixtures.current();ReviewedLocalFixtures.price(changed,81,9000);when(snapshots.readProducts(authority.ids(),authority.businessSourceId())).thenReturn(current,changed);
        assertEquals(409,assertThrows(ResponseStatusException.class,()->service.similar(81,true)).getStatusCode().value());
    }
}
