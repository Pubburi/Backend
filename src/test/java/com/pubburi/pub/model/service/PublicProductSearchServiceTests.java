package com.pubburi.pub.model.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.List;
import java.util.Optional;
import com.pubburi.pub.controller.api.*;
import com.pubburi.pub.model.dao.ProductSearchIdentityDao;
import com.pubburi.pub.model.dto.Product;
import com.pubburi.pub.model.dto.ProductSuggestion;
import com.pubburi.pub.search.ProductSearchGateway;
import com.pubburi.pub.search.ProductSearchHint;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class PublicProductSearchServiceTests {
    static final PageCriteria PAGE=PageCriteria.of(1,12,12);

    @Test void verifiedAliasPreservesTheOriginalPageAndEveryMandatoryCondition() {
        var db = mock(ProductService.class);
        var identities = mock(ProductSearchIdentityDao.class);
        var engine = mock(ProductSearchGateway.class);
        var filters = ProductSearchFilters.fromQuery("1000", "1800", "3", "7.5",
            new String[]{"dry,fruit", "tea"}, new String[]{"floral"});
        var original = PageResponse.<Product>of(List.of(), PAGE, 0);
        var probe = new PageCriteria(1, 1);
        var match = new Product(7, "Authored", "약 · 청주", 1500, "authored.png", 7);
        when(db.searchProducts("약 · 청주", " 약주 ", "priceDesc", filters, PAGE)).thenReturn(original);
        when(db.searchProducts("약 · 청주", "약 · 청주", "priceDesc", filters, probe))
            .thenReturn(PageResponse.of(List.of(match), probe, 1));

        var result = new PublicProductSearchService(db, identities, engine)
            .search("약 · 청주", " 약주 ", "priceDesc", filters, PAGE);
        assertThat(result.page()).isSameAs(original);
        assertThat(result.source()).isEqualTo(PublicProductSearchService.Source.DATABASE);
        assertThat(result.hint().headerValue()).isEqualTo("ALIAS:%EC%95%BD%20%C2%B7%20%EC%B2%AD%EC%A3%BC");
        var reads = inOrder(db);
        reads.verify(db).searchProducts("약 · 청주", " 약주 ", "priceDesc", filters, PAGE);
        reads.verify(db).searchProducts("약 · 청주", "약 · 청주", "priceDesc", filters, probe);
        reads.verifyNoMoreInteractions();
        verifyNoInteractions(identities);
        verify(engine).enabled();
        verifyNoMoreInteractions(engine);
    }

    @Test void zeroResultsDoNotSuggestAmbiguousExactShortOrNonliteralTerms() {
        var db = mock(ProductService.class);
        var filters = ProductSearchFilters.none();
        var service = new PublicProductSearchService(db, () -> null, ProductSearchGateway.disabled());
        var empty = PageResponse.<Product>of(List.of(), PAGE, 0);
        for (String term : List.of("", "약", "탁쥬", "과류주", "과실주", "약주%", "과실쥬_", "과실쥬\\", "과실쥬 탁주", "과실쥬1", "가".repeat(21))) {
            when(db.searchProducts(null, term, null, filters, PAGE)).thenReturn(empty);
            assertThat(service.search(null, term, null, filters, PAGE).hint()).isNull();
            verify(db).searchProducts(null, term, null, filters, PAGE);
        }
        var outOfRange = new PageCriteria(3, 1);
        when(db.searchProducts(null, "과실쥬", null, filters, outOfRange))
            .thenReturn(PageResponse.of(List.of(), outOfRange, 2));
        assertThat(service.search(null, "과실쥬", null, filters, outOfRange).hint()).isNull();
        verify(db).searchProducts(null, "과실쥬", null, filters, outOfRange);
        verifyNoMoreInteractions(db);
        assertThat(ProductSearchHint.forQuery("과실쥬").headerValue()).isEqualTo("TYPO:%EA%B3%BC%EC%8B%A4%EC%A3%BC");
    }

    @Test void optionalVerificationFailureLeavesTheOriginalPageAndPrimaryFailureStillPropagates() {
        var db = mock(ProductService.class);
        var filters = ProductSearchFilters.none();
        var service = new PublicProductSearchService(db, () -> null, ProductSearchGateway.disabled());
        var empty = PageResponse.<Product>of(List.of(), PAGE, 0);
        var failure = new DataAccessResourceFailureException("Authored database read failure");
        when(db.searchProducts(null, "과실쥬", null, filters, PAGE)).thenReturn(empty).thenThrow(failure);
        when(db.searchProducts(null, "과실주", null, filters, new PageCriteria(1, 1))).thenThrow(failure);

        var result = service.search(null, "과실쥬", null, filters, PAGE);
        assertThat(result.page()).isSameAs(empty);
        assertThat(result.hint()).isNull();
        assertThatThrownBy(() -> service.search(null, "과실쥬", null, filters, PAGE)).isSameAs(failure);
        verify(db).searchProducts(null, "과실주", null, filters, new PageCriteria(1, 1));
    }
    @Test void suggestionsNormalizeBeforeIoAndDisabledOrShortInputNeedsNoSource() {
        var db=mock(ProductService.class);var identity=mock(ProductSearchIdentityDao.class);var engine=mock(ProductSearchGateway.class);
        var service=new PublicProductSearchService(db,identity,engine);
        assertThat(service.suggest("\u00a0한\u00a0").items()).isEmpty();verifyNoInteractions(db,identity,engine);
        assertThatThrownBy(()->service.suggest("한\n글")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->service.suggest("x".repeat(101))).isInstanceOf(IllegalArgumentException.class);
        when(db.suggestByName("한글")).thenReturn(List.of(new ProductSuggestion(1,"한글","탁주",1000)));
        var result=service.suggest("\u00a0한글\u00a0");
        assertThat(result.source()).isEqualTo(PublicProductSearchService.Source.DATABASE);assertThat(result.items()).hasSize(1);
        verifyNoInteractions(identity);verify(engine).enabled();verifyNoMoreInteractions(engine);
    }
    @Test void suggestionsRequireExactCurrentOrderedIdsAndUseOnlyCurrentFactsAfterRemoteIo() {
        var db=mock(ProductService.class);var identity=mock(ProductSearchIdentityDao.class);var engine=mock(ProductSearchGateway.class);
        when(engine.enabled()).thenReturn(true);when(identity.selectSourceId()).thenReturn("source");
        var current=List.of(new ProductSuggestion(2,"한글","탁주",9000),new ProductSuggestion(5,"한글","약주",2000));
        when(db.suggestByName("한글")).thenReturn(current);
        when(engine.suggest("source","한글")).thenAnswer(invocation->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();return Optional.of(new ProductSearchGateway.Selection(List.of(2,5),2));});
        var service=new PublicProductSearchService(db,identity,engine);var result=service.suggest(" 한글 ");
        assertThat(result.source()).isEqualTo(PublicProductSearchService.Source.ENGINE_VERIFIED);assertThat(result.items()).isSameAs(current);
        var order=inOrder(identity,engine,db);order.verify(engine).enabled();order.verify(identity).selectSourceId();order.verify(engine).suggest("source","한글");order.verify(db).suggestByName("한글");
        for(var selection:List.of(Optional.<ProductSearchGateway.Selection>empty(),Optional.of(new ProductSearchGateway.Selection(List.of(2),1)),Optional.of(new ProductSearchGateway.Selection(List.of(5,2),2)))){
            when(engine.suggest("source","한글")).thenReturn(selection);result=service.suggest("한글");
            assertThat(result.source()).isEqualTo(PublicProductSearchService.Source.DATABASE_FALLBACK);assertThat(result.items()).isSameAs(current);
        }
        when(db.suggestByName("한글")).thenThrow(new IllegalStateException("DB failure"));
        assertThatThrownBy(()->service.suggest("한글")).isInstanceOf(IllegalStateException.class);
    }
    @Test void disabledSearchPreservesTheCanonicalCallWithoutReadingSourceOrEngine() {
        var db=mock(ProductService.class);var identity=mock(ProductSearchIdentityDao.class);var engine=mock(ProductSearchGateway.class);
        var expected=PageResponse.of(List.of(new Product(1,"current","맥주",1000,"current.png",5)),PAGE,1);
        when(db.searchProducts(" 맥주 "," % ","unknown",ProductSearchFilters.none(),PAGE)).thenReturn(expected);
        var result=new PublicProductSearchService(db,identity,engine).search(" 맥주 "," % ","unknown",ProductSearchFilters.none(),PAGE);
        assertThat(result.source()).isEqualTo(PublicProductSearchService.Source.DATABASE);assertThat(result.page()).isSameAs(expected);
        verifyNoInteractions(identity);verify(engine).enabled();verifyNoMoreInteractions(engine);
    }
    @Test void verifiedEngineIdsNeverSupplyStaleProductFieldsOrAWeakerCurrentFilter() {
        var db=mock(ProductService.class);var identity=mock(ProductSearchIdentityDao.class);when(identity.selectSourceId()).thenReturn("source");
        var filters=ProductSearchFilters.fromQuery("0","10000","0","10",new String[]{"a,b","line\nx"},null);
        var current=PageResponse.of(List.of(new Product(2,"latest","맥주",7777,"latest.png",5)),PAGE,1);
        when(db.searchProducts(" 맥주 "," 술 ","priceAsc",filters,PAGE)).thenReturn(current);
        ProductSearchGateway engine=new ProductSearchGateway(){public boolean enabled(){return true;}public Optional<Selection> search(String source,String type,String q,String sort,ProductSearchFilters sent,PageCriteria page){
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();assertThat(source).isEqualTo("source");assertThat(type).isEqualTo("맥주");assertThat(q).isEqualTo("술");assertThat(sent).isSameAs(filters);return Optional.of(new Selection(List.of(2),1));}};
        var result=new PublicProductSearchService(db,identity,engine).search(" 맥주 "," 술 ","priceAsc",filters,PAGE);
        assertThat(result.source()).isEqualTo(PublicProductSearchService.Source.ENGINE_VERIFIED);assertThat(result.page()).isSameAs(current);assertThat(result.page().items().get(0).getPrice()).isEqualTo(7777);
        verify(db,times(1)).searchProducts(" 맥주 "," 술 ","priceAsc",filters,PAGE);
    }
    @Test void missingDeletedReorderedOrWrongTotalsUseExactlyTheCurrentCanonicalPage() {
        var db=mock(ProductService.class);var identity=mock(ProductSearchIdentityDao.class);var engine=mock(ProductSearchGateway.class);when(engine.enabled()).thenReturn(true);
        when(identity.selectSourceId()).thenReturn("source");var current=PageResponse.of(List.of(new Product(2,"current","맥주",1000,"current.png",5),new Product(5,"current","맥주",2000,"current.png",5)),PAGE,2);
        when(db.searchProducts(null,null,null,ProductSearchFilters.none(),PAGE)).thenReturn(current);
        for(var choice:List.of(Optional.<ProductSearchGateway.Selection>empty(),Optional.of(new ProductSearchGateway.Selection(List.of(2),2)),Optional.of(new ProductSearchGateway.Selection(List.of(5,2),2)),Optional.of(new ProductSearchGateway.Selection(List.of(2,5),3)))){
            when(engine.search("source",null,null,null,ProductSearchFilters.none(),PAGE)).thenReturn(choice);
            var result=new PublicProductSearchService(db,identity,engine).search(null,null,null,ProductSearchFilters.none(),PAGE);
            assertThat(result.source()).isEqualTo(PublicProductSearchService.Source.DATABASE_FALLBACK);assertThat(result.page()).isSameAs(current);
        }
    }
    @Test void dbFailuresAndInvalidOffsetsCannotBecomeEmptySuccesses() {
        var db=mock(ProductService.class);var identity=mock(ProductSearchIdentityDao.class);var engine=mock(ProductSearchGateway.class);when(engine.enabled()).thenReturn(true);
        var service=new PublicProductSearchService(db,identity,engine);
        assertThatThrownBy(()->service.search(null,null,null,ProductSearchFilters.none(),new PageCriteria(Integer.MAX_VALUE,100))).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(identity,db);
        when(identity.selectSourceId()).thenThrow(new IllegalStateException("DB failure"));
        assertThatThrownBy(()->service.search(null,null,null,ProductSearchFilters.none(),PAGE)).isInstanceOf(IllegalStateException.class);
        doReturn("source").when(identity).selectSourceId();when(engine.search("source",null,null,null,ProductSearchFilters.none(),PAGE)).thenReturn(Optional.empty());
        when(db.searchProducts(null,null,null,ProductSearchFilters.none(),PAGE)).thenThrow(new IllegalStateException("DB failure"));
        assertThatThrownBy(()->service.search(null,null,null,ProductSearchFilters.none(),PAGE)).isInstanceOf(IllegalStateException.class);
    }
}
