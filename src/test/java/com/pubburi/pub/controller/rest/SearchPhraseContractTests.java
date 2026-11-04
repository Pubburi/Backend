package com.pubburi.pub.controller.rest;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.mockito.Mockito.*;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pubburi.pub.controller.api.GlobalExceptionHandler;
import com.pubburi.pub.controller.auth.*;
import com.pubburi.pub.model.service.*;
import com.pubburi.pub.recommendation.SearchPhraseFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class SearchPhraseContractTests {
    static final String PATH = "/api/users/me/search-phrases";
    @Test void ownerCannotBeInjectedAndTypesChoicesAndDefaultsAreStrict() throws Exception {
        var user = SessionFixtures.user("own", "ADMIN"); var session = new MockHttpSession();
        session.setAttribute(AccessGuard.SESSION_USER, SessionUser.from(user));
        var preferences = mock(UserPreferenceService.class); when(preferences.getOwned(user.getId(), user.getAuthEpoch())).thenReturn(SearchPhraseFixtures.preferences());
        var calls = new AtomicInteger();
        var service = new SearchPhraseService(preferences, (snapshot, fields, limit) -> { calls.incrementAndGet(); assertThat(fields).isEmpty(); assertThat(limit).isEqualTo(5); return Optional.empty(); }, new ObjectMapper());
        var mvc = MockMvcBuilders.standaloneSetup(new SearchPhraseController(SessionFixtures.guard(user), service)).setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(post(PATH).session(session).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "private, no-store"))
            .andExpect(jsonPath("$.data.source").value("UNAVAILABLE")).andExpect(jsonPath("$.data.result").isEmpty());
        for (String invalid : List.of("{\"userId\":\"other\"}", "{\"preferences\":{}}", "{\"authEpoch\":\"other\"}",
                "{\"limit\":null}", "{\"limit\":true}", "{\"limit\":\"1\"}", "{\"limit\":1.0}", "{\"limit\":0}", "{\"limit\":6}",
                "{\"searchOptInFields\":null}", "{\"searchOptInFields\":[null]}", "{\"searchOptInFields\":[1]}",
                "{\"searchOptInFields\":[\"unknown\"]}", "{\"searchOptInFields\":[\"tasteTags\",\"tasteTags\"]}"))
            mvc.perform(post(PATH).session(session).contentType(MediaType.APPLICATION_JSON).content(invalid)).andExpect(status().isBadRequest());
        mvc.perform(post(PATH+"?userId=other").session(session).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest());
        assertThat(calls).hasValue(1);
    }
    @Test void unauthenticatedExpiredAndSameSessionAccountReplacementCannotReceivePrivateResults() throws Exception {
        var own = SessionFixtures.user("own", "USER"); var other = SessionFixtures.user("other", "USER");
        var session = new MockHttpSession(); session.setAttribute(AccessGuard.SESSION_USER, SessionUser.from(own));
        var preferences = mock(UserPreferenceService.class); when(preferences.getOwned(own.getId(), own.getAuthEpoch())).thenReturn(SearchPhraseFixtures.preferences());
        var calls = new AtomicInteger();
        var service = new SearchPhraseService(preferences, (snapshot, fields, limit) -> {
            calls.incrementAndGet(); session.setAttribute(AccessGuard.SESSION_USER, SessionUser.from(other)); return Optional.empty();
        }, new ObjectMapper());
        var mvc = MockMvcBuilders.standaloneSetup(new SearchPhraseController(SessionFixtures.guard(own, other), service)).setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized()); assertThat(calls).hasValue(0);
        mvc.perform(post(PATH).session(session).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized()); assertThat(calls).hasValue(1);
        session.setAttribute(AccessGuard.SESSION_USER,new SessionUser("own","own",0,"USER","expired"));
        mvc.perform(post(PATH).session(session).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized()); assertThat(calls).hasValue(1);
    }
}
