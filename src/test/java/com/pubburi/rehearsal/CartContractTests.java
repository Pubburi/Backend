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

class CartContractTests {
    static final String PATH="/api/users/me/cart",HEADER=NotebookController.OWNER_HEADER;
    static final SessionUser OWNER=new SessionUser("own","Owner",0,"USER","00000000-0000-4000-8000-000000000001");
    @Test void ownerAndQueryGuardsArePrivateAndDeleteReturnsTheNewCart()throws Exception{
        var guard=mock(AccessGuard.class);when(guard.requireLogin(any())).thenReturn(OWNER);var carts=mock(CartService.class);
        when(carts.get(OWNER)).thenReturn(new CartService.Cart(0,List.of()));when(carts.remove(OWNER,null,0)).thenReturn(new CartService.Cart(1,List.of()));
        var mvc=MockMvcBuilders.standaloneSetup(new CartController(guard,carts)).setControllerAdvice(new GlobalExceptionHandler()).addFilters(new NotebookPrivacyFilter()).build();
        mvc.perform(get(PATH).header(HEADER,"own")).andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(0)).andExpect(header().string("Cache-Control","private, no-store"));
        mvc.perform(get(PATH)).andExpect(status().isBadRequest()).andExpect(header().string("Vary","Cookie"));
        mvc.perform(get(PATH).header(HEADER,"other")).andExpect(status().isUnauthorized()).andExpect(header().string("Cache-Control","private, no-store"));
        for(String suffix:List.of("?owner=other","?expectedVersion=0"))mvc.perform(get(PATH+suffix).header(HEADER,"own")).andExpect(status().isBadRequest());
        for(String suffix:List.of("/operations/not-a-uuid","/operations/00000000-0-4000-8000-1","/operations/00000000-0000-4000-8000-000000000001?owner=other"))mvc.perform(get(PATH+suffix).header(HEADER,"own")).andExpect(status().isBadRequest()).andExpect(header().string("Cache-Control","private, no-store"));
        for(String suffix:List.of("","?expectedVersion=0&expectedVersion=1","?expectedVersion=1.0","?expectedVersion=9007199254740992"))mvc.perform(delete(PATH+suffix).header(HEADER,"own")).andExpect(status().isBadRequest()).andExpect(header().string("Cache-Control","private, no-store"));
        mvc.perform(delete(PATH+"?expectedVersion=0").header(HEADER,"own")).andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(1));
        when(carts.remove(OWNER,null,0)).thenThrow(new ResponseStatusException(HttpStatus.CONFLICT));mvc.perform(delete(PATH+"?expectedVersion=0").header(HEADER,"own")).andExpect(status().isConflict()).andExpect(header().string("Cache-Control","private, no-store"));
        when(guard.requireLogin(any())).thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED));mvc.perform(post(PATH+"/merge").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized()).andExpect(header().string("Cache-Control","private, no-store"));
    }
    @Test void boundedBodiesCannotReachCartMutationEvenBeforeMvcAuthentication()throws Exception{
        var guard=mock(AccessGuard.class);when(guard.requireLogin(any())).thenReturn(OWNER);var carts=mock(CartService.class);
        var mvc=MockMvcBuilders.standaloneSetup(new CartController(guard,carts)).setControllerAdvice(new GlobalExceptionHandler()).addFilters(new NotebookPrivacyFilter()).build();
        String body="{\"items\":\""+"x".repeat(NotebookPrivacyFilter.MAX_BODY_BYTES)+"\"}";
        for(String path:List.of(PATH+"/merge",PATH+";matrix=1/checkout","/api/users/me/%63art/merge"))mvc.perform(post(java.net.URI.create(path)).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isPayloadTooLarge()).andExpect(header().string("Cache-Control","private, no-store"));
        mvc.perform(context->{var request=new org.springframework.mock.web.MockHttpServletRequest(context,"POST",PATH+"/merge"){
            @Override public long getContentLengthLong(){return -1;} @Override public int getContentLength(){return -1;}
        };request.setContentType(MediaType.APPLICATION_JSON_VALUE);request.addHeader(HEADER,"own");request.setContent(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));return request;}).andExpect(status().isBadRequest()).andExpect(header().string("Cache-Control","private, no-store"));
        verifyNoInteractions(carts);
    }
}
