package com.pubburi.pub.controller.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pubburi.pub.controller.api.CommentSort;
import com.pubburi.pub.controller.api.GlobalExceptionHandler;
import com.pubburi.pub.model.dao.CommentDao;
import com.pubburi.pub.model.dto.Comment;
import com.pubburi.pub.model.service.CommentServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class CommentPageContractTests {
    final CommentDao dao = mock(CommentDao.class);
    final MockMvc mvc = MockMvcBuilders.standaloneSetup(new CommentRestController(new CommentServiceImpl(dao), null))
            .setControllerAdvice(new GlobalExceptionHandler()).build();
    final ObjectMapper mapper = new ObjectMapper();

    Set<String> fields(JsonNode value) {
        return StreamSupport.stream(((Iterable<String>) () -> value.fieldNames()).spliterator(), false).collect(Collectors.toSet());
    }
    JsonNode data(String query) throws Exception {
        return mapper.readTree(mvc.perform(get("/api/products/7/comments" + query)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).path("data");
    }
    void page() {
        when(dao.selectByProductIdPaged(anyInt(), anyInt(), anyInt(), any())).thenReturn(List.of(new Comment(12, "author", 7, 3.75f, "authored")));
        when(dao.countByProductId(7)).thenReturn(12);
    }

    @Test void omittedAndFalseSummaryKeepSixPageAndFiveReviewFields() throws Exception {
        page();
        for (String query : List.of("", "?includeSummary=false", "?sort=latest&includeSummary=false")) {
            var page = data(query);
            assertThat(fields(page)).containsExactlyInAnyOrder("items", "page", "size", "total", "totalPages", "hasNext");
            assertThat(fields(page.path("items").get(0))).containsExactlyInAnyOrder("id", "userId", "productId", "rating", "comment");
            assertThat(page.path("items").get(0).path("rating").floatValue()).isEqualTo(3.75f);
        }
        verify(dao, never()).averageRatingByProductId(anyInt());
    }

    @Test void wholeProductAggregateDoesNotUseOnlyTheVisiblePage() throws Exception {
        page(); when(dao.averageRatingByProductId(7)).thenReturn(2.675d);
        var page = data("?page=2&size=1&sort=rating_desc&includeSummary=true");
        assertThat(page.size()).isEqualTo(7); assertThat(page.path("total").intValue()).isEqualTo(12);
        assertThat(page.path("ratingSummary").path("count").intValue()).isEqualTo(12);
        assertThat(page.path("ratingSummary").path("average").decimalValue()).isEqualByComparingTo("2.68");
        assertThat(page.path("totalPages").intValue()).isEqualTo(12); assertThat(page.path("hasNext").booleanValue()).isTrue();
        verify(dao).selectByProductIdPaged(7, 1, 1, CommentSort.RATING_DESC);
    }

    @Test void emptyAndOutOfRangePagesRetainWholeSummaryAndMissingProductStaysEmpty() throws Exception {
        when(dao.selectByProductIdPaged(anyInt(), anyInt(), anyInt(), any())).thenReturn(List.of());
        when(dao.countByProductId(7)).thenReturn(2); when(dao.averageRatingByProductId(7)).thenReturn(0d);
        var later = data("?page=9&size=1&includeSummary=true&sort=rating_asc");
        assertThat(later.path("items")).isEmpty(); assertThat(later.path("ratingSummary").path("count").intValue()).isEqualTo(2);
        assertThat(later.path("ratingSummary").path("average").decimalValue()).isEqualByComparingTo("0");
        var missing = mapper.readTree(mvc.perform(get("/api/products/0/comments?includeSummary=true"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
        assertThat(missing.path("total").intValue()).isZero(); assertThat(missing.path("ratingSummary").path("count").intValue()).isZero();
        assertThat(missing.path("ratingSummary").path("average").isNull()).isTrue();
    }

    @Test void legacyFiniteAveragesAreNeitherClippedNorFiltered() throws Exception {
        page();
        for (double value : List.of(-1.25, 5.555, 100.125)) {
            when(dao.averageRatingByProductId(7)).thenReturn(value);
            var average = data("?includeSummary=true").path("ratingSummary").path("average").decimalValue();
            assertThat(average).isEqualByComparingTo(java.math.BigDecimal.valueOf(value).setScale(2, java.math.RoundingMode.HALF_UP));
        }
    }

    @Test void invalidOrRepeatedOptionsFailBeforeDatabaseReads() throws Exception {
        for (String value : List.of("", "POPULAR", "latest ", "rating", "rating_desc,id", "rating_desc;DELETE"))
            mvc.perform(get("/api/products/7/comments").param("sort", value)).andExpect(status().isBadRequest());
        for (String value : List.of("", "TRUE", "1", "yes", "false ", "null"))
            mvc.perform(get("/api/products/7/comments").param("includeSummary", value)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/products/7/comments").param("sort", "latest", "latest")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/products/7/comments").param("includeSummary", "true", "true")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/products/7/comments?page=2147483647&size=100")).andExpect(status().isBadRequest());
        verifyNoInteractions(dao);
    }

    @Test void invalidNonemptyAggregateFailsRatherThanHidingAsNoReviews() throws Exception {
        page();
        for (Double value : new Double[] {null, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            when(dao.averageRatingByProductId(7)).thenReturn(value);
            mvc.perform(get("/api/products/7/comments?includeSummary=true")).andExpect(status().isInternalServerError());
        }
    }
}
