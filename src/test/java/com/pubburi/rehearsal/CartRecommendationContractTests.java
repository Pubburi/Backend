package com.pubburi.rehearsal;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.util.List;
import com.pubburi.pub.controller.api.GlobalExceptionHandler;
import com.pubburi.pub.controller.auth.*;
import com.pubburi.pub.controller.response.CartRecommendationResponse;
import com.pubburi.pub.controller.response.CartRecommendationResponse.UnavailableReason;
import com.pubburi.pub.controller.response.ContentRecommendationResponse.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

class CartRecommendationContractTests {
    @Test void privateOwnerStrictInputAndPostRemoteSessionChecksNeverMutateACart()throws Exception{
        var owner=CartContractTests.OWNER;var guard=mock(AccessGuard.class);when(guard.requireLogin(any())).thenReturn(owner);
        var service=mock(CartRecommendationService.class);when(service.recommend(eq(owner),any())).thenReturn(new CartRecommendationResponse(Source.UNAVAILABLE,CatalogScope.REVIEWED_DEMO,0,List.of(),UnavailableReason.EMPTY_CART,null,List.of()));
        var mvc=MockMvcBuilders.standaloneSetup(new CartRecommendationController(guard,service)).setControllerAdvice(new GlobalExceptionHandler()).addFilters(new NotebookPrivacyFilter()).build();
        String path="/api/users/me/cart/recommendations",header=NotebookController.OWNER_HEADER;
        mvc.perform(post(path).header(header,owner.id()).contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":0}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.unavailableReason").value("EMPTY_CART")).andExpect(header().string("Cache-Control","private, no-store")).andExpect(header().string("Vary","Cookie"));
        clearInvocations(service);
        mvc.perform(post(path).header(header,"other").contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":0}")).andExpect(status().isUnauthorized());
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":0}")).andExpect(status().isBadRequest());
        mvc.perform(post(path+"?owner=other").header(header,owner.id()).contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":0}")).andExpect(status().isBadRequest());
        mvc.perform(post(path).header(header,owner.id()).contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":true}")).andExpect(status().isBadRequest());
        mvc.perform(post(path).header(header,owner.id()).contentType(MediaType.APPLICATION_JSON).content("x".repeat(NotebookPrivacyFilter.MAX_BODY_BYTES+1))).andExpect(status().isPayloadTooLarge()).andExpect(header().string("Cache-Control","private, no-store"));
        verifyNoInteractions(service);
        when(guard.requireLogin(any())).thenReturn(owner).thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        mvc.perform(post(path).header(header,owner.id()).contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":0}"))
            .andExpect(status().isUnauthorized()).andExpect(header().string("Cache-Control","private, no-store"));
    }
}
