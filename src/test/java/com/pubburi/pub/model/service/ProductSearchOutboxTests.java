package com.pubburi.pub.model.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.sql.Connection;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pubburi.pub.model.dao.ProductDao;
import com.pubburi.pub.model.dao.ProductSearchOutboxDao;
import com.pubburi.pub.model.dto.Product;
import com.pubburi.pub.model.dto.ProductFeatures;
import com.pubburi.pub.model.dto.ProductFeatureTag;

class ProductSearchOutboxTests {
    final ProductDao products = mock(ProductDao.class);
    final ProductSearchOutboxDao events = mock(ProductSearchOutboxDao.class);
    final ObjectMapper json = new ObjectMapper();
    final ProductSearchOutbox outbox = new ProductSearchOutbox(products, events, json);

    void writable() {
        when(events.advanceVersion(7)).thenReturn(1);
        when(events.selectVersionForUpdate(7)).thenReturn(9L);
        when(events.append(eq(7), eq(9L), anyBoolean(), anyString())).thenReturn(1);
        when(events.schedule(7, 9L)).thenReturn(1);
    }

    @Test void currentSnapshotPreservesUnknownReviewedEmptyAndOnlyApprovedIndexFields() throws Exception {
        writable();
        when(products.selectByIdForUpdate(7)).thenReturn(new Product(7, "Fixture", "탁주", 1000, "private-image", 5.555f, 2));
        ProductFeatures features = new ProductFeatures();
        features.setAromaKnown(true); features.setSourceName("Not index data");
        when(events.selectFeaturesForUpdate(7)).thenReturn(features);
        when(events.selectTagsForUpdate(7)).thenReturn(List.of());
        outbox.recordCurrent(7);
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        var order = inOrder(products, events);
        order.verify(products).selectByIdForUpdate(7);
        order.verify(events).selectFeaturesForUpdate(7);
        order.verify(events).selectTagsForUpdate(7);
        order.verify(events).ensureVersion(7);
        order.verify(events).advanceVersion(7);
        order.verify(events).selectVersionForUpdate(7);
        order.verify(events).append(eq(7), eq(9L), eq(false), payload.capture());
        order.verify(events).schedule(7, 9L);
        JsonNode event = json.readTree(payload.getValue());
        assertThat(event.size()).isEqualTo(4);
        assertThat(event.path("sourceVersion").asLong()).isEqualTo(9);
        JsonNode document = event.path("document");
        assertThat(document.size()).isEqualTo(7);
        assertThat(document.path("tasteTags").isNull()).isTrue();
        assertThat(document.path("aromaTags").isArray()).isTrue();
        assertThat(document.path("aromaTags").isEmpty()).isTrue();
        assertThat(document.path("abvPercent").floatValue()).isEqualTo(5.555f);
        assertThat(payload.getValue()).doesNotContain("private-image", "Not index data", "sourceName", "userId");
    }

    @Test void knownTagsAreSortedAndTombstoneContainsNoOldProductFields() throws Exception {
        writable();
        when(products.selectByIdForUpdate(7)).thenReturn(new Product(7, "Fixture", "탁주", 1000, "x", 0));
        ProductFeatures features = new ProductFeatures(); features.setTasteKnown(true);
        when(events.selectFeaturesForUpdate(7)).thenReturn(features);
        when(events.selectTagsForUpdate(7)).thenReturn(List.of(new ProductFeatureTag("TASTE", "sweet"), new ProductFeatureTag("TASTE", "dry")));
        outbox.recordCurrent(7);
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(events).append(eq(7), eq(9L), eq(false), payload.capture());
        assertThat(json.readTree(payload.getValue()).path("document").path("tasteTags").toString()).isEqualTo("[\"dry\",\"sweet\"]");
        when(products.selectByIdForUpdate(7)).thenReturn(null);
        outbox.recordDeleted(7);
        verify(events).append(eq(7), eq(9L), eq(true), payload.capture());
        JsonNode tombstone = json.readTree(payload.getValue());
        assertThat(tombstone.path("deleted").asBoolean()).isTrue();
        assertThat(tombstone.path("document").isNull()).isTrue();
        assertThat(tombstone.size()).isEqualTo(4);
    }

    @Test void missingLiveProductOrStillLiveDeletionCannotProduceAnEvent() {
        assertThatThrownBy(() -> outbox.recordCurrent(7)).isInstanceOf(IllegalStateException.class);
        when(products.selectByIdForUpdate(7)).thenReturn(new Product());
        assertThatThrownBy(() -> outbox.recordDeleted(7)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(events);
    }

    @Test void exhaustedVersionAndFailedAppendAbortRatherThanCommitWithoutARecord() {
        assertThatThrownBy(() -> outbox.recordDeleted(7)).isInstanceOf(IllegalStateException.class);
        verify(events, never()).append(anyInt(), anyLong(), anyBoolean(), anyString());
        writable(); when(events.append(eq(7), eq(9L), eq(true), anyString())).thenReturn(0);
        assertThatThrownBy(() -> outbox.recordDeleted(7)).isInstanceOf(IllegalStateException.class);
    }

    @Test void unscheduledDeliveryAbortsTheSameBusinessTransaction() {
        writable(); when(events.schedule(7, 9L)).thenReturn(0);
        assertThatThrownBy(() -> outbox.recordDeleted(7)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("scheduled");
    }

    @Test void publisherRejectsNoTransactionAndJoinsTheCallersRollback() throws Exception {
        DataSource source = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        when(source.getConnection()).thenReturn(connection); when(connection.getAutoCommit()).thenReturn(true);
        var manager = new DataSourceTransactionManager(source);
        ProxyFactory factory = new ProxyFactory(outbox);
        factory.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        ProductSearchOutbox proxied = (ProductSearchOutbox) factory.getProxy();
        assertThatThrownBy(() -> proxied.recordDeleted(7)).isInstanceOf(IllegalTransactionStateException.class);
        verifyNoInteractions(events);
        writable();
        new TransactionTemplate(manager).execute(status -> {
            proxied.recordDeleted(7);
            status.setRollbackOnly();
            return null;
        });
        verify(source).getConnection();
        verify(connection).rollback();
        verify(connection, never()).commit();
    }

    @Test void productWritesPublishOnlyAfterSuccessfulBusinessMutation() {
        ProductSearchOutbox publisher = mock(ProductSearchOutbox.class);
        ProductServiceImpl service = new ProductServiceImpl(products, publisher);
        Product product = new Product(7, "Fixture", "탁주", 1000, "x", 6);
        when(products.insert(product)).thenReturn(1);
        when(products.selectByIdForUpdate(7)).thenReturn(product);
        when(products.update(product)).thenReturn(1); when(products.delete(7)).thenReturn(1);
        service.addProduct(product); service.updateProduct(product); service.removeProduct(7);
        var order = inOrder(products, publisher);
        order.verify(products).insert(product); order.verify(publisher).recordCurrent(7);
        order.verify(products).selectByIdForUpdate(7); order.verify(products).update(product); order.verify(publisher).recordCurrent(7);
        order.verify(products).selectByIdForUpdate(7); order.verify(products).delete(7); order.verify(publisher).recordDeleted(7);
        when(products.selectByIdForUpdate(7)).thenReturn(null);
        assertThat(service.updateProduct(product)).isZero(); assertThat(service.removeProduct(7)).isZero();
        verifyNoMoreInteractions(publisher);
    }
}
