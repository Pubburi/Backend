package com.pubburi.pub.controller.rest;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.pubburi.pub.controller.auth.NotebookPrivacyFilter;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class UnavailableNotebookTests {
    @Test void legacyRuntimeHasExplicitPrivateNotFoundRatherThanInventedPersistence()throws Exception{
        var mvc=MockMvcBuilders.standaloneSetup(new UnavailableNotebookController()).addFilters(new NotebookPrivacyFilter()).build();
        for(String path:new String[]{"/api/users/me/notebook","/api/users/me/notebook/by-product/1","/api/users/me/notebook/00000000-0000-4000-8000-000000000001"})
            mvc.perform(get(path)).andExpect(status().isNotFound()).andExpect(header().string("Cache-Control","private, no-store")).andExpect(header().string("Vary","Cookie")).andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }
}
