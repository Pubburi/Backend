package com.pubburi.pub.controller.rest;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.util.List;
import com.pubburi.pub.controller.api.GlobalExceptionHandler;
import com.pubburi.pub.controller.response.SimilarProductsResponse;
import com.pubburi.pub.model.service.SimilarProductService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class SimilarProductQueryTests {
    @Test void absentOrFalseKeepsTheLegacyWireAndOnlyLiteralTrueRequestsReasons()throws Exception{
        var service=mock(SimilarProductService.class);
        when(service.similar(81)).thenReturn(new SimilarProductsResponse(List.of()));
        when(service.similar(81,true)).thenReturn(new SimilarProductsResponse(List.of(),null,List.of()));
        var mvc=MockMvcBuilders.standaloneSetup(new SimilarProductController(service)).setControllerAdvice(new GlobalExceptionHandler()).build();
        for(String path:List.of("/api/products/81/similar","/api/products/81/similar?includeExplanations=false"))
            mvc.perform(get(path)).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("$.data.items").isEmpty()).andExpect(jsonPath("$.data.explanations").doesNotExist());
        mvc.perform(get("/api/products/81/similar?includeExplanations=true")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items").isEmpty()).andExpect(jsonPath("$.data.explanations").isEmpty());
        verify(service,times(2)).similar(81);verify(service).similar(81,true);verifyNoMoreInteractions(service);
    }
    @Test void duplicateUnknownAndNonliteralSelectorsFailBeforeReadingProducts()throws Exception{
        var service=mock(SimilarProductService.class);var mvc=MockMvcBuilders.standaloneSetup(new SimilarProductController(service)).setControllerAdvice(new GlobalExceptionHandler()).build();
        for(String value:List.of("","TRUE","True","1","null"," true","false "))
            mvc.perform(get("/api/products/81/similar").param("includeExplanations",value)).andExpect(status().isBadRequest()).andExpect(header().string("Cache-Control","no-store"));
        mvc.perform(get("/api/products/81/similar").param("includeExplanations","true","true")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/products/81/similar").param("includeExplanations","true").param("referenceId","82")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/products/81/similar").param("limit","1")).andExpect(status().isBadRequest());verifyNoInteractions(service);
    }
}
