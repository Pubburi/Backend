package com.pubburi.rehearsal;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.pubburi.pub.controller.api.*;
import com.pubburi.pub.controller.auth.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

class NotebookContractTests {
    static final String PATH="/api/users/me/notebook";
    static final String ID="00000000-0000-4000-8000-000000000001";
    static final SessionUser OWNER=new SessionUser("own","Owner",0,"USER","00000000-0000-4000-8000-000000000002");
    @Test void accountAndRevisionSelectorsAreStrictAndEveryOutcomeIsPrivate()throws Exception{
        var guard=mock(AccessGuard.class);var notes=mock(NotebookService.class);when(guard.requireLogin(any())).thenReturn(OWNER);
        when(notes.page(eq(OWNER),any(),isNull())).thenReturn(PageResponse.of(List.of(),new PageCriteria(1,20),0));
        var mvc=MockMvcBuilders.standaloneSetup(new NotebookController(guard,notes)).setControllerAdvice(new GlobalExceptionHandler()).addFilters(new NotebookPrivacyFilter()).build();
        var session=new MockHttpSession();
        mvc.perform(get(PATH).session(session).header(NotebookController.OWNER_HEADER,"own")).andExpect(status().isOk()).andExpect(header().string("Cache-Control","private, no-store")).andExpect(jsonPath("$.data.items").isEmpty());
        mvc.perform(get(PATH).session(session)).andExpect(status().isBadRequest()).andExpect(header().string("Cache-Control","private, no-store"));
        mvc.perform(get(PATH).session(session).header(NotebookController.OWNER_HEADER,"other")).andExpect(status().isUnauthorized()).andExpect(header().string("Vary","Cookie"));
        for(String suffix:List.of("?page=0","?size=101","?page=2147483647&size=100","?page=1&page=2","?owner=other"))mvc.perform(get(PATH+suffix).session(session).header(NotebookController.OWNER_HEADER,"own")).andExpect(status().isBadRequest()).andExpect(header().string("Cache-Control","private, no-store"));
        for(String suffix:List.of("","?expectedVersion=0&expectedVersion=1","?expectedVersion=-1","?expectedVersion=1.0","?expectedVersion=9007199254740992"))mvc.perform(delete(PATH+"/"+ID+suffix).session(session).header(NotebookController.OWNER_HEADER,"own")).andExpect(status().isBadRequest()).andExpect(header().string("Cache-Control","private, no-store"));
        mvc.perform(delete(PATH+"/"+ID+"?expectedVersion=0").session(session).header(NotebookController.OWNER_HEADER,"own")).andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty());verify(notes).delete(OWNER,UUID.fromString(ID),0);
        doThrow(new ResponseStatusException(HttpStatus.CONFLICT,"Notebook entry changed")).when(notes).delete(OWNER,UUID.fromString(ID),0);
        mvc.perform(delete(PATH+"/"+ID+"?expectedVersion=0").session(session).header(NotebookController.OWNER_HEADER,"own")).andExpect(status().isConflict()).andExpect(header().string("Cache-Control","private, no-store"));
        when(guard.requireLogin(any())).thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized()).andExpect(header().string("Cache-Control","private, no-store"));
    }
}
