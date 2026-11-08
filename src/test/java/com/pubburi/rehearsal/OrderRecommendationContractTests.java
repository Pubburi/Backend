package com.pubburi.rehearsal;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.util.List;
import com.pubburi.pub.controller.api.GlobalExceptionHandler;
import com.pubburi.pub.controller.auth.*;
import com.pubburi.pub.controller.response.OrderRecommendationResponse;
import com.pubburi.pub.controller.response.OrderRecommendationResponse.UnavailableReason;
import com.pubburi.pub.controller.response.ContentRecommendationResponse.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

class OrderRecommendationContractTests {
    @Test void strictOwnedReadAndEveryInputOrSessionFailureRemainPrivate()throws Exception{
        var owner=NotebookContractTests.OWNER;var guard=mock(AccessGuard.class);when(guard.requireLogin(any())).thenReturn(owner);var service=mock(OrderRecommendationService.class);
        when(service.recommend(eq(owner),eq(7),any())).thenReturn(new OrderRecommendationResponse(Source.UNAVAILABLE,CatalogScope.REVIEWED_DEMO,7,0,List.of(),UnavailableReason.EMPTY_ORDER,null,List.of()));
        var mvc=MockMvcBuilders.standaloneSetup(new OrderRecommendationController(guard,service)).setControllerAdvice(new GlobalExceptionHandler()).addFilters(new NotebookPrivacyFilter()).build();
        String path="/api/users/me/orders/7/recommendations",header=NotebookController.OWNER_HEADER;
        mvc.perform(post(path).header(header,owner.id()).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.referenceCount").value(0)).andExpect(jsonPath("$.data.result").isEmpty()).andExpect(header().string("Cache-Control","private, no-store")).andExpect(header().string("Vary","Cookie"));
        verify(service).recommend(owner,7,new OrderRecommendationInput(4));clearInvocations(service);
        for(String input:List.of("null","[]","{\"limit\":null}","{\"limit\":true}","{\"limit\":\"4\"}","{\"limit\":1.0}","{\"limit\":0}","{\"limit\":13}","{\"limit\":2147483648}","{\"limit\":1,\"limit\":2}","{\"productIds\":[1]}","{\"expectedVersion\":1}","{} {}"))
            mvc.perform(post(path).header(header,owner.id()).contentType(MediaType.APPLICATION_JSON).content(input)).andExpect(status().isBadRequest()).andExpect(header().string("Cache-Control","private, no-store"));
        mvc.perform(post(path+"?owner=other").header(header,owner.id()).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest());
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest());
        mvc.perform(post(path).header(header,"other").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized());
        mvc.perform(post(path).header(header,owner.id()).contentType(MediaType.APPLICATION_JSON).content("x".repeat(NotebookPrivacyFilter.MAX_BODY_BYTES+1))).andExpect(status().isPayloadTooLarge()).andExpect(header().string("Cache-Control","private, no-store"));verifyNoInteractions(service);
        when(guard.requireLogin(any())).thenReturn(owner).thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        mvc.perform(post(path).header(header,owner.id()).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized()).andExpect(header().string("Cache-Control","private, no-store"));
    }
}
