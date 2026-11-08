package com.pubburi.pub.model.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import com.pubburi.pub.recommendation.*;
import com.pubburi.pub.controller.response.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class OwnedContentRecommendationServiceTests {
    @Test void stableNamespaceIsCheckedInsideSnapshotReadsAndNeverAddedToLegacyReads(){
        var identity=mock(com.pubburi.pub.model.dao.ProductSearchIdentityDao.class);
        var snapshots=new OwnedContentSnapshotService(mock(UserPreferenceService.class),mock(com.pubburi.pub.model.dao.ProductDao.class),mock(com.pubburi.pub.model.dao.ProductFeatureDao.class),
            mock(com.pubburi.pub.model.dao.FavoriteDao.class),mock(com.pubburi.pub.model.dao.CommentDao.class),ContentFixtures.JSON,Optional.empty(),identity);
        assertThat(snapshots.readProducts(List.of())).isEmpty();verifyNoInteractions(identity);
        when(identity.selectSourceId()).thenReturn(ReviewedLocalFixtures.NAMESPACE);assertThat(snapshots.readProducts(List.of(),ReviewedLocalFixtures.NAMESPACE)).isEmpty();
        when(identity.selectSourceId()).thenReturn("00000000-0000-4000-8000-000000000002");
        assertThatThrownBy(()->snapshots.readProducts(List.of(),ReviewedLocalFixtures.NAMESPACE)).isInstanceOfSatisfying(ResponseStatusException.class,e->assertThat(e.getStatusCode().value()).isEqualTo(409));
        when(identity.selectSourceId()).thenThrow(new IllegalStateException("Owned source read failure"));assertThatThrownBy(()->snapshots.readProducts(List.of(),ReviewedLocalFixtures.NAMESPACE)).isInstanceOf(IllegalStateException.class);
    }
    @Test void stableAuthorityUsesCurrentPriceAndDeletedRowsWithoutReissuingAnOldBusinessSnapshot()throws Exception{
        var authority=ReviewedLocalFixtures.catalog();var facts=ReviewedLocalFixtures.current();ReviewedLocalFixtures.price(facts,81,7000);
        var snapshots=mock(OwnedContentSnapshotService.class);var current=new OwnedContentSnapshotService.Snapshot(ReviewedLocalFixtures.preferences(),facts,null);
        when(snapshots.read("own","epoch",authority.ids(),List.of(),false,authority.businessSourceId())).thenReturn(current);
        var service=new OwnedContentRecommendationService(snapshots,()->Optional.of(authority),(c,p,l,a)->{
            assertThat(c.at("/0/price_krw").intValue()).isEqualTo(7000);assertThat(c.at("/0/taste_tags").isNull()).isTrue();assertThat(c.get(0).has("description")).isFalse();
            try{return Optional.of(ReviewedLocalFixtures.response(2));}catch(Exception error){throw new AssertionError(error);}
        },ContentFixtures.JSON);
        var result=service.recommend("own","epoch",1,false);assertThat(result.catalogScope().name()).isEqualTo("REVIEWED_LOCAL_CATALOG");assertThat(result.products().get(0).price()).isEqualTo(7000);
        verify(snapshots,times(2)).read("own","epoch",authority.ids(),List.of(),false,authority.businessSourceId());
        facts=ReviewedLocalFixtures.current();facts.remove(82);current=new OwnedContentSnapshotService.Snapshot(ReviewedLocalFixtures.preferences(),facts,null);
        when(snapshots.read("own","epoch",authority.ids(),List.of(),false,authority.businessSourceId())).thenReturn(current);
        var deleted=new OwnedContentRecommendationService(snapshots,()->Optional.of(authority),(c,p,l,a)->{
            assertThat(c.at("/1/available").booleanValue()).isFalse();assertThat(c.at("/1/price_krw").isNull()).isTrue();
            try{return Optional.of(ReviewedLocalFixtures.response(1));}catch(Exception error){throw new AssertionError(error);}
        },ContentFixtures.JSON).recommend("own","epoch",1,false);
        assertThat(deleted.source().name()).isEqualTo("PROVIDER");assertThat(deleted.products()).extracting(ProductResponse::id).containsExactly(81);
    }
    @Test void stableProviderFailureKeepsItsScopeButPriceOrAuthorityChangesAre409()throws Exception{
        var authority=ReviewedLocalFixtures.catalog();var snapshots=mock(OwnedContentSnapshotService.class);var current=new OwnedContentSnapshotService.Snapshot(ReviewedLocalFixtures.preferences(),ReviewedLocalFixtures.current(),null);
        when(snapshots.read("own","epoch",authority.ids(),List.of(),false,authority.businessSourceId())).thenReturn(current);
        var selected=new java.util.concurrent.atomic.AtomicReference<Optional<ReviewedLocalCatalog>>(Optional.of(authority));
        var service=new OwnedContentRecommendationService(snapshots,selected::get,(c,p,l,a)->Optional.empty(),ContentFixtures.JSON);
        assertThat(service.recommend("own","epoch",1,false).catalogScope().name()).isEqualTo("REVIEWED_LOCAL_CATALOG");
        var changed=ReviewedLocalFixtures.current();ReviewedLocalFixtures.price(changed,81,9000);
        when(snapshots.read("own","epoch",authority.ids(),List.of(),false,authority.businessSourceId())).thenReturn(current,new OwnedContentSnapshotService.Snapshot(current.preferences(),changed,null));
        assertThatThrownBy(()->service.recommend("own","epoch",1,false)).isInstanceOfSatisfying(ResponseStatusException.class,e->assertThat(e.getStatusCode().value()).isEqualTo(409));
        when(snapshots.read("own","epoch",authority.ids(),List.of(),false,authority.businessSourceId())).thenReturn(current);
        var broken=new OwnedContentRecommendationService(snapshots,selected::get,(c,p,l,a)->{selected.set(Optional.empty());return Optional.empty();},ContentFixtures.JSON);
        assertThatThrownBy(()->broken.recommend("own","epoch",1,false)).isInstanceOfSatisfying(ResponseStatusException.class,e->assertThat(e.getStatusCode().value()).isEqualTo(409));
    }
    @Test void stableScopeStillUsesSavedNegativeFeedbackInsteadOfUnfilteredFallback()throws Exception{
        var authority=ReviewedLocalFixtures.catalog();var signals=new FeedbackSignals(List.of(),List.of(81));var snapshots=mock(OwnedContentSnapshotService.class);var current=new OwnedContentSnapshotService.Snapshot(ReviewedLocalFixtures.preferences(),ReviewedLocalFixtures.current(),null,signals);
        when(snapshots.read("own","epoch",authority.ids(),List.of(),true,authority.businessSourceId())).thenReturn(current);var gateway=mock(ContentGateway.class);
        when(gateway.feedback(any(),eq(current.preferences()),eq(1),isNull(),eq(signals))).thenReturn(Optional.empty());
        var result=new OwnedContentRecommendationService(snapshots,()->Optional.of(authority),gateway,ContentFixtures.JSON).recommend("own","epoch",1,false,true);
        assertThat(result.catalogScope().name()).isEqualTo("REVIEWED_LOCAL_CATALOG");verify(gateway).feedback(any(),eq(current.preferences()),eq(1),isNull(),eq(signals));verifyNoMoreInteractions(gateway);
    }
    static OwnedContentSnapshotService.Snapshot snapshot(ContentCatalogSource.Catalog catalog){var facts=new LinkedHashMap<Integer,OwnedContentSnapshotService.Facts>();catalog.bindings().forEach((id,binding)->{try{facts.put(id,new OwnedContentSnapshotService.Facts(binding.product(),binding.features(),ContentFixtures.JSON.treeToValue(binding.product(),ProductResponse.class)));}catch(Exception invalid){throw new AssertionError(invalid);}});return new OwnedContentSnapshotService.Snapshot(ContentFixtures.preferences(),facts,null);}
    @Test void onlyCheckedCatalogAndCurrentOwnedPreferencesGoUpstreamAndNoWeakerFallbackIsGenerated()throws Exception{
        var catalog=ContentFixtures.catalog();var snapshots=mock(OwnedContentSnapshotService.class);var current=snapshot(catalog);when(snapshots.read("own","epoch",catalog.ids(),List.of())).thenReturn(current);
        var service=new OwnedContentRecommendationService(snapshots,()->Optional.of(catalog),(c,p,l,a)->{assertThat(c).isEqualTo(catalog.catalog());assertThat(l).isEqualTo(4);assertThat(p).isEqualTo(current.preferences());assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();try{return Optional.of(ContentFixtures.response());}catch(Exception error){throw new AssertionError(error);}},ContentFixtures.JSON);
        var response=service.recommend("own","epoch",4,false);assertThat(response.source().name()).isEqualTo("PROVIDER");assertThat(response.products()).extracting(ProductResponse::id).containsExactly(81);verify(snapshots,times(2)).read("own","epoch",catalog.ids(),List.of());
        var unavailable=new OwnedContentRecommendationService(snapshots,()->Optional.of(catalog),(c,p,l,a)->Optional.empty(),ContentFixtures.JSON).recommend("own","epoch",4,false);assertThat(unavailable.source().name()).isEqualTo("UNAVAILABLE");assertThat(unavailable.result()).isNull();assertThat(unavailable.products()).isEmpty();
    }
    @Test void changedCatalogOrPreferencesExpiredIdentityAndDatabaseErrorsStayDistinct()throws Exception{
        var catalog=ContentFixtures.catalog();var snapshots=mock(OwnedContentSnapshotService.class);var current=snapshot(catalog);var service=new OwnedContentRecommendationService(snapshots,()->Optional.of(catalog),(c,p,l,a)->Optional.empty(),ContentFixtures.JSON);
        when(snapshots.read("own","epoch",catalog.ids(),List.of())).thenReturn(current,new OwnedContentSnapshotService.Snapshot(current.preferences(),Map.of(),null));
        assertThatThrownBy(()->service.recommend("own","epoch",4,false)).isInstanceOfSatisfying(ResponseStatusException.class,e->assertThat(e.getStatusCode().value()).isEqualTo(409));
        var p=new com.pubburi.pub.model.dto.UserPreferences();p.setSurveyStatus("COMPLETED");p.setMaxPriceKrw(0);when(snapshots.read("own","epoch",catalog.ids(),List.of())).thenReturn(current,new OwnedContentSnapshotService.Snapshot(UserPreferencesResponse.from(p),current.products(),null));
        assertThatThrownBy(()->service.recommend("own","epoch",4,false)).isInstanceOfSatisfying(ResponseStatusException.class,e->assertThat(e.getStatusCode().value()).isEqualTo(409));
        when(snapshots.read("own","epoch",catalog.ids(),List.of())).thenThrow(new ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED));assertThatThrownBy(()->service.recommend("own","epoch",4,false)).isInstanceOf(ResponseStatusException.class);
        doThrow(new IllegalStateException("DB failure")).when(snapshots).read("own","epoch",catalog.ids(),List.of());assertThatThrownBy(()->service.recommend("own","epoch",4,false)).isInstanceOf(IllegalStateException.class);
    }
    @Test void approvedFamilyKeepsUnknownFactsAndOnlyProviderUsesTheExplicitBrowseScope()throws Exception{
        var catalog=PinnedContentCatalog.validate(ContentFixtures.familyBundle(),"c".repeat(64),ContentFixtures.NOW,true);
        var snapshots=mock(OwnedContentSnapshotService.class);when(snapshots.read("own","epoch",catalog.ids(),List.of())).thenReturn(snapshot(catalog));
        var service=new OwnedContentRecommendationService(snapshots,()->Optional.of(catalog),(c,p,l,a)->{
            assertThat(c.at("/0/volume_ml").isNull()).isTrue();assertThat(c.at("/0/taste_tags").isNull()).isTrue();
            try{return Optional.of(ContentFixtures.response());}catch(Exception invalid){throw new AssertionError(invalid);}
        },ContentFixtures.JSON);
        var result=service.recommend("own","epoch",4,false);assertThat(result.catalogScope().name()).isEqualTo("REVIEWED_FAMILY_BROWSE_DEMO");
        assertThat(result.products()).extracting(ProductResponse::id).containsExactly(81);
        var unavailable=new OwnedContentRecommendationService(snapshots,()->Optional.of(catalog),(c,p,l,a)->Optional.empty(),ContentFixtures.JSON).recommend("own","epoch",4,false);
        assertThat(unavailable.catalogScope().name()).isEqualTo("REVIEWED_DEMO");assertThat(unavailable.result()).isNull();assertThat(unavailable.products()).isEmpty();
    }

    @Test void feedbackOverflowAndProviderFailureNeverIssueUnfilteredRecommendations() throws Exception {
        var catalog = ContentFixtures.catalog();
        var current = snapshot(catalog);
        var snapshots = mock(OwnedContentSnapshotService.class);
        var gateway = mock(ContentGateway.class);
        var service = new OwnedContentRecommendationService(snapshots, () -> Optional.of(catalog), gateway, ContentFixtures.JSON);
        var overflow = java.util.stream.IntStream.rangeClosed(1, 21).boxed().toList();
        for (var signals : List.of(new FeedbackSignals(List.of(), overflow), new FeedbackSignals(overflow, List.of()))) {
            when(snapshots.read("own", "epoch", catalog.ids(), List.of(), true)).thenReturn(
                new OwnedContentSnapshotService.Snapshot(current.preferences(), current.products(), null, signals));
            assertThat(service.recommend("own", "epoch", 4, false, true).source().name()).isEqualTo("UNAVAILABLE");
            verifyNoInteractions(gateway);
        }
        var negative = new FeedbackSignals(List.of(), List.of(81));
        when(snapshots.read("own", "epoch", catalog.ids(), List.of(), true)).thenReturn(
            new OwnedContentSnapshotService.Snapshot(current.preferences(), current.products(), null, negative));
        when(gateway.feedback(catalog.catalog(), current.preferences(), 4, null, negative)).thenReturn(Optional.empty());
        assertThat(service.recommend("own", "epoch", 4, false, true).source().name()).isEqualTo("UNAVAILABLE");
        verify(gateway).feedback(catalog.catalog(), current.preferences(), 4, null, negative);
        verifyNoMoreInteractions(gateway);
    }

}
