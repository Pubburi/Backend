package com.pubburi.pub.controller.rest;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.pubburi.pub.controller.auth.NotebookPrivacyFilter;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class UnavailableNotificationsTests {
    @Test void legacySupportSignalIsExplicitAndPrivate(){
        var mvc=MockMvcBuilders.standaloneSetup(new UnavailableNotificationsController()).addFilters(new NotebookPrivacyFilter()).build();
        try{
            mvc.perform(get("/api/users/me/notifications")).andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("NOTIFICATIONS_NOT_SUPPORTED")).andExpect(header().string("Cache-Control","private, no-store"));
            mvc.perform(post("/api/users/me/notifications/read")).andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        }catch(Exception error){throw new AssertionError(error);}
    }
}
