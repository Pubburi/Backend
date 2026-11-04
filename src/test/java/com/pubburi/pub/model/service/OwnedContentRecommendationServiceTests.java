package com.pubburi.pub.model.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import com.pubburi.pub.recommendation.*;
import com.pubburi.pub.controller.response.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class OwnedContentRecommendationServiceTests {
    static OwnedContentSnapshotService.Snapshot snapshot(ContentCatalogSource.Catalog catalog){var facts=new LinkedHashMap<Integer,OwnedContentSnapshotService.Facts>();catalog.bindings().forEach((id,binding)->{try{facts.put(id,new OwnedContentSnapshotService.Facts(binding.product(),binding.features(),ContentFixtures.JSON.treeToValue(binding.product(),ProductResponse.class)));}catch(Exception invalid){throw new AssertionError(invalid);}});return new OwnedContentSnapshotService.Snapshot(ContentFixtures.preferences(),facts);}
    @Test void onlyCheckedCatalogAndCurrentOwnedPreferencesGoUpstreamAndNoWeakerFallbackIsGenerated()throws Exception{
        var catalog=ContentFixtures.catalog();var snapshots=mock(OwnedContentSnapshotService.class);var current=snapshot(catalog);when(snapshots.read("own","epoch",catalog.ids())).thenReturn(current);
        var service=new OwnedContentRecommendationService(snapshots,()->Optional.of(catalog),(c,p,l)->{assertThat(c).isEqualTo(catalog.catalog());assertThat(l).isEqualTo(4);assertThat(p).isEqualTo(current.preferences());assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();try{return Optional.of(ContentFixtures.response());}catch(Exception error){throw new AssertionError(error);}},ContentFixtures.JSON);
        var response=service.recommend("own","epoch",4);assertThat(response.source().name()).isEqualTo("PROVIDER");assertThat(response.products()).extracting(ProductResponse::id).containsExactly(81);verify(snapshots,times(2)).read("own","epoch",catalog.ids());
        var unavailable=new OwnedContentRecommendationService(snapshots,()->Optional.of(catalog),(c,p,l)->Optional.empty(),ContentFixtures.JSON).recommend("own","epoch",4);assertThat(unavailable.source().name()).isEqualTo("UNAVAILABLE");assertThat(unavailable.result()).isNull();assertThat(unavailable.products()).isEmpty();
    }
    @Test void changedCatalogOrPreferencesExpiredIdentityAndDatabaseErrorsStayDistinct()throws Exception{
        var catalog=ContentFixtures.catalog();var snapshots=mock(OwnedContentSnapshotService.class);var current=snapshot(catalog);var service=new OwnedContentRecommendationService(snapshots,()->Optional.of(catalog),(c,p,l)->Optional.empty(),ContentFixtures.JSON);
        when(snapshots.read("own","epoch",catalog.ids())).thenReturn(current,new OwnedContentSnapshotService.Snapshot(current.preferences(),Map.of()));
        assertThatThrownBy(()->service.recommend("own","epoch",4)).isInstanceOfSatisfying(ResponseStatusException.class,e->assertThat(e.getStatusCode().value()).isEqualTo(409));
        var p=new com.pubburi.pub.model.dto.UserPreferences();p.setSurveyStatus("COMPLETED");p.setMaxPriceKrw(0);when(snapshots.read("own","epoch",catalog.ids())).thenReturn(current,new OwnedContentSnapshotService.Snapshot(UserPreferencesResponse.from(p),current.products()));
        assertThatThrownBy(()->service.recommend("own","epoch",4)).isInstanceOfSatisfying(ResponseStatusException.class,e->assertThat(e.getStatusCode().value()).isEqualTo(409));
        when(snapshots.read("own","epoch",catalog.ids())).thenThrow(new ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED));assertThatThrownBy(()->service.recommend("own","epoch",4)).isInstanceOf(ResponseStatusException.class);
        doThrow(new IllegalStateException("DB failure")).when(snapshots).read("own","epoch",catalog.ids());assertThatThrownBy(()->service.recommend("own","epoch",4)).isInstanceOf(IllegalStateException.class);
    }
}
