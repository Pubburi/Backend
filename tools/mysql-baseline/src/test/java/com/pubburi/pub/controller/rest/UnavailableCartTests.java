package com.pubburi.pub.controller.rest;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.pubburi.pub.controller.api.GlobalExceptionHandler;
import com.pubburi.pub.controller.auth.NotebookPrivacyFilter;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class UnavailableCartTests {
    @Test void onlyLegacyBaseGetAdvertisesExplicitUnsupportedCapability()throws Exception{
        var mvc=MockMvcBuilders.standaloneSetup(new UnavailableCartController()).setControllerAdvice(new GlobalExceptionHandler()).addFilters(new NotebookPrivacyFilter()).build();
        mvc.perform(get("/api/users/me/cart")).andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("CART_NOT_SUPPORTED")).andExpect(header().string("Cache-Control","private, no-store"));
        mvc.perform(post("/api/users/me/cart/merge")).andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("NOT_FOUND")).andExpect(header().string("Cache-Control","private, no-store"));
    }
}
