package com.pubburi.pub.controller.rest;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.mockito.Mockito.*;
import java.util.*;
import com.pubburi.pub.controller.api.GlobalExceptionHandler;
import com.pubburi.pub.controller.auth.*;
import com.pubburi.pub.controller.response.ContentRecommendationResponse;
import com.pubburi.pub.model.service.OwnedContentRecommendationService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class OwnedContentContractTests {
    static final String PATH="/api/users/me/recommendations/content";
    @Test void strictPrivateLimitAndOptInDoNotAcceptOwnerProfileCatalogOrSelectors()throws Exception{
        var user=SessionFixtures.user("own","ADMIN");var session=new MockHttpSession();session.setAttribute(AccessGuard.SESSION_USER,SessionUser.from(user));var service=mock(OwnedContentRecommendationService.class);
        when(service.recommend(user.getId(),user.getAuthEpoch(),4,false)).thenReturn(ContentRecommendationResponse.unavailable());
        var mvc=MockMvcBuilders.standaloneSetup(new OwnedContentRecommendationController(SessionFixtures.guard(user),service)).setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(post(PATH).session(session).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isOk()).andExpect(header().string("Cache-Control","private, no-store")).andExpect(header().string("Vary","Cookie")).andExpect(jsonPath("$.data.source").value("UNAVAILABLE")).andExpect(jsonPath("$.data.catalogScope").value("REVIEWED_DEMO")).andExpect(jsonPath("$.data.result").isEmpty()).andExpect(jsonPath("$.data.products").isEmpty()).andExpect(jsonPath("$.data.activityEvidence").doesNotExist());
        when(service.recommend(user.getId(),user.getAuthEpoch(),4,true)).thenReturn(ContentRecommendationResponse.unavailable());
        mvc.perform(post(PATH).session(session).contentType(MediaType.APPLICATION_JSON).content("{\"includeActivity\":true}")).andExpect(status().isOk());
        verify(service).recommend(user.getId(),user.getAuthEpoch(),4,true);
        for(String body:List.of("{\"includeActivity\":null}","{\"includeActivity\":\"true\"}","{\"includeActivity\":1}","{\"includeActivity\":[]}","{\"activity_categories\":{}}","{\"id\":\"other\"}","{\"preferences\":{}}","{\"profile\":{}}","{\"catalog\":[]}","{\"include_explanations\":false}","{\"limit\":null}","{\"limit\":true}","{\"limit\":\"1\"}","{\"limit\":1.0}","{\"limit\":0}","{\"limit\":13}"))mvc.perform(post(PATH).session(session).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        mvc.perform(post(PATH+"?userId=other").session(session).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest());verify(service,times(1)).recommend(user.getId(),user.getAuthEpoch(),4,false);
    }
    @Test void guestExpiredEpochAndSameSessionReplacementCannotReceivePrivateResults()throws Exception{
        var own=SessionFixtures.user("own","USER");var other=SessionFixtures.user("other","USER");var session=new MockHttpSession();session.setAttribute(AccessGuard.SESSION_USER,SessionUser.from(own));var service=mock(OwnedContentRecommendationService.class);
        when(service.recommend(own.getId(),own.getAuthEpoch(),4,false)).thenAnswer(call->{session.setAttribute(AccessGuard.SESSION_USER,SessionUser.from(other));return ContentRecommendationResponse.unavailable();});
        var mvc=MockMvcBuilders.standaloneSetup(new OwnedContentRecommendationController(SessionFixtures.guard(own,other),service)).setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized());verifyNoInteractions(service);
        mvc.perform(post(PATH).session(session).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized());
        session.setAttribute(AccessGuard.SESSION_USER,new SessionUser("own","own",0,"USER","expired"));mvc.perform(post(PATH).session(session).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized());verify(service,times(1)).recommend(own.getId(),own.getAuthEpoch(),4,false);
    }
}
