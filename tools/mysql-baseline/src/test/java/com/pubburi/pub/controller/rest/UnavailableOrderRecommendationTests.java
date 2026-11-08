package com.pubburi.pub.controller.rest;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.pubburi.pub.controller.auth.NotebookPrivacyFilter;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class UnavailableOrderRecommendationTests {
    @Test void legacyCapabilityIsExplicitAndNeverReturnsAFabricatedRecommendation()throws Exception{
        var mvc=MockMvcBuilders.standaloneSetup(new UnavailableOrderRecommendationController()).addFilters(new NotebookPrivacyFilter()).build();
        mvc.perform(post("/api/users/me/orders/7/recommendations")).andExpect(status().isNotFound())
            .andExpect(jsonPath("$.data").isEmpty()).andExpect(jsonPath("$.error.code").value("ORDER_CONTEXT_NOT_SUPPORTED"))
            .andExpect(header().string("Cache-Control","private, no-store")).andExpect(header().string("Vary","Cookie"));
    }
}
