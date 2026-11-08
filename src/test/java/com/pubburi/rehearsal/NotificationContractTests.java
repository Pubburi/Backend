package com.pubburi.rehearsal;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.pubburi.pub.controller.api.*;
import com.pubburi.pub.controller.auth.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

class NotificationContractTests {
    static final String PATH="/api/users/me/notifications",HEADER=NotebookController.OWNER_HEADER;
    static final SessionUser OWNER=new SessionUser("own","Owner",0,"USER",UUID.randomUUID().toString());
    @Test void ownScopeStrictPageAndReadContractsAlwaysKeepPrivateFailures()throws Exception{
        var guard=mock(AccessGuard.class);when(guard.requireLogin(any())).thenReturn(OWNER);var service=mock(NotificationService.class);
        UUID id=UUID.randomUUID();when(service.page(eq(OWNER),any())).thenReturn(new NotificationService.Page(List.of(),1,20,0,0,false,7));
        when(service.read(OWNER,List.of(id))).thenReturn(new NotificationService.ReadResult(List.of(new NotificationService.ReadItem(id,23)),6));
        var mvc=MockMvcBuilders.standaloneSetup(new NotificationController(guard,service)).setControllerAdvice(new GlobalExceptionHandler()).addFilters(new NotebookPrivacyFilter()).build();
        mvc.perform(get(PATH).header(HEADER,"own")).andExpect(status().isOk()).andExpect(jsonPath("$.data.unreadCount").value(7)).andExpect(header().string("Cache-Control","private, no-store"));
        mvc.perform(get(PATH)).andExpect(status().isBadRequest()).andExpect(header().string("Vary","Cookie"));
        mvc.perform(get(PATH).header(HEADER,"other")).andExpect(status().isUnauthorized()).andExpect(header().string("Cache-Control","private, no-store"));
        for(String suffix:List.of("?owner=other","?page=1&page=2","?page=0","?size=101","?page=2147483647&size=100","?size=1.0","?page=+1"))
            mvc.perform(get(PATH+suffix).header(HEADER,"own")).andExpect(status().isBadRequest()).andExpect(header().string("Cache-Control","private, no-store"));
        mvc.perform(put(PATH+"/"+id+"/read").header(HEADER,"own")).andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].readAt").value(23));
        mvc.perform(put(PATH+"/"+id+"/read").header(HEADER,"own").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isBadRequest()).andExpect(header().string("Cache-Control","private, no-store"));
        mvc.perform(put(PATH+"/not-uuid/read").header(HEADER,"own")).andExpect(status().isBadRequest());
        when(service.read(eq(OWNER),any())).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));
        mvc.perform(post(PATH+"/read").header(HEADER,"own").contentType(MediaType.APPLICATION_JSON).content("{\"ids\":[\""+id+"\"]}"))
            .andExpect(status().isNotFound()).andExpect(header().string("Cache-Control","private, no-store"));
        when(guard.requireLogin(any())).thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        mvc.perform(get(PATH).header(HEADER,"own")).andExpect(status().isUnauthorized()).andExpect(header().string("Cache-Control","private, no-store"));
    }
    @Test void preAuthenticationOversizeBodiesRemainPrivateAndNeverReachMutations()throws Exception{
        var service=mock(NotificationService.class);var mvc=MockMvcBuilders.standaloneSetup(new NotificationController(mock(AccessGuard.class),service)).setControllerAdvice(new GlobalExceptionHandler()).addFilters(new NotebookPrivacyFilter()).build();
        mvc.perform(post(PATH+"/read").contentType(MediaType.APPLICATION_JSON).content("x".repeat(NotebookPrivacyFilter.MAX_BODY_BYTES+1)))
            .andExpect(status().isPayloadTooLarge()).andExpect(header().string("Cache-Control","private, no-store"));
        verifyNoInteractions(service);
    }
}
