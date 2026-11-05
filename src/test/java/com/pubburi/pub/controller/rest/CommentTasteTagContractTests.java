package com.pubburi.pub.controller.rest;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pubburi.pub.controller.api.*;
import com.pubburi.pub.controller.auth.*;
import com.pubburi.pub.controller.request.*;
import com.pubburi.pub.model.dao.CommentDao;
import com.pubburi.pub.model.dto.Comment;
import com.pubburi.pub.model.service.CommentServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class CommentTasteTagContractTests {
    final ObjectMapper json = new ObjectMapper();
    @Test void missingIsDifferentFromNullAndAtomsUseExistingTagRules() throws Exception {
        String base = "\"productId\":7,\"rating\":3.75,\"comment\":\"body\"";
        assertThat(json.readValue("{" + base + "}", CommentCreateRequest.class).tasteTags()).isNull();
        var value = json.readValue("{" + base + ",\"tasteTags\":[\"  SWEET  \",\"과일,향\\n산미\",\"Ａ\",\"İ\"]}", CommentCreateRequest.class);
        assertThat(value.tasteTags()).containsExactly("i\u0307", "sweet", "과일,향\n산미", "ａ");
        assertThat(json.readValue("{\"rating\":1.25,\"comment\":\"edit\"}", CommentUpdateRequest.class).tasteTags()).isNull();
        assertThat(json.readValue("{\"rating\":1.25,\"comment\":\"edit\",\"tasteTags\":[]}", CommentUpdateRequest.class).tasteTags()).isEmpty();
        for (Object invalid : Arrays.asList(null, "tag", 1, true, Map.of("tag", "x"), Arrays.asList((Object)null), List.of(1), List.of(true), List.of(" "), List.of("SWEET", "sweet"), List.of("x".repeat(41)), List.of("nul\u0000tag"), Collections.nCopies(21, "x"))) {
            var body = new LinkedHashMap<String,Object>(); body.put("productId",7);body.put("rating",3.75);body.put("comment","body");body.put("tasteTags",invalid);
            assertThatThrownBy(() -> json.readValue(json.writeValueAsBytes(body), CommentCreateRequest.class)).isInstanceOf(Exception.class);
        }
        assertThat(CommentTasteTags.normalize(List.of("가".repeat(40)))).containsExactly("가".repeat(40));
        assertThatThrownBy(() -> CommentTasteTags.normalize(List.of("😀".repeat(21)))).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
    @Test void createAndReplacementAreAtomicServiceCallsButOmissionPreservesOpinions() {
        CommentDao dao = mock(CommentDao.class);var service = new CommentServiceImpl(dao);
        var value = new Comment(9,"author",7,3.75f,"body");value.setTasteTags(List.of(" SWEET ","산미"));
        when(dao.insert(value)).thenReturn(1);when(dao.update(value)).thenReturn(1);
        assertThat(service.addComment(value)).isEqualTo(1);verify(dao).insertTasteTags(9,List.of("sweet","산미"));
        clearInvocations(dao);value.setTasteTags(null);assertThat(service.updateComment(value)).isEqualTo(1);
        verify(dao,never()).deleteTasteTags(anyInt());verify(dao,never()).insertTasteTags(anyInt(),any());
        value.setTasteTags(List.of());service.updateComment(value);verify(dao).deleteTasteTags(9);verify(dao,never()).insertTasteTags(anyInt(),any());
        clearInvocations(dao);value.setTasteTags(List.of("SWEET","sweet"));
        assertThatThrownBy(() -> service.updateComment(value)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);verifyNoInteractions(dao);
    }
    @Test void invalidHttpBodiesAreRejectedBeforeMutatingAndWritingKeepsFiveFields() throws Exception {
        CommentDao dao=mock(CommentDao.class);AccessGuard guard=mock(AccessGuard.class);
        when(guard.requireLogin(any())).thenReturn(new SessionUser("author","name",0,"USER","epoch"));
        when(dao.insert(any())).thenAnswer(call -> {call.<Comment>getArgument(0).setId(9);return 1;});
        var mvc=MockMvcBuilders.standaloneSetup(new CommentRestController(new CommentServiceImpl(dao),guard)).setControllerAdvice(new GlobalExceptionHandler()).build();
        for (String invalid : List.of("null", "[1]", "[\"Sweet\",\"sweet\"]", "[\" \"]", "{}"))
            mvc.perform(post("/api/comments").contentType("application/json").content("{\"productId\":7,\"rating\":3.75,\"comment\":\"body\",\"tasteTags\":"+invalid+"}")).andExpect(status().isBadRequest());
        verifyNoInteractions(dao);
        var result=mvc.perform(post("/api/comments").contentType("application/json").content("{\"productId\":7,\"rating\":3.75,\"comment\":\"body\",\"tasteTags\":[\" SWEET \"]}")).andExpect(status().isCreated()).andReturn();
        assertThat(json.readTree(result.getResponse().getContentAsString()).path("data").size()).isEqualTo(5);
        verify(dao).insertTasteTags(9,List.of("sweet"));
    }
    @Test void rawEscapedLoneSurrogatesFailBeforeWritesAndAValidPairRemainsExact() throws Exception {
        CommentDao dao=mock(CommentDao.class);AccessGuard guard=mock(AccessGuard.class);
        when(guard.requireLogin(any())).thenReturn(new SessionUser("author","name",0,"USER","epoch"));
        when(dao.insert(any())).thenAnswer(call->{call.<Comment>getArgument(0).setId(9);return 1;});
        var mvc=MockMvcBuilders.standaloneSetup(new CommentRestController(new CommentServiceImpl(dao),guard)).setControllerAdvice(new GlobalExceptionHandler()).build();
        for(String escape:List.of("\\ud800","\\udfff","\\ud800a","\\udfff\\ud800")) {
            String raw="{\"productId\":7,\"rating\":3.75,\"comment\":\"unchanged\",\"tasteTags\":[\""+escape+"\"]}";
            assertThat(raw.chars().allMatch(value->value<128)).isTrue();
            assertThat(raw).contains(escape);
            mvc.perform(post("/api/comments").contentType("application/json").content(raw)).andExpect(status().isBadRequest());
            String edit=raw.replace("\"productId\":7,","");
            assertThatThrownBy(()->json.readValue(edit,CommentUpdateRequest.class)).isInstanceOf(Exception.class);
        }
        verifyNoInteractions(dao);
        String valid="{\"productId\":7,\"rating\":3.75,\"comment\":\"paired\",\"tasteTags\":[\"\\ud83c\\udf3e\"]}";
        assertThat(valid.chars().allMatch(value->value<128)).isTrue();
        assertThat(json.readValue(valid.replace("\"productId\":7,",""),CommentUpdateRequest.class).tasteTags()).containsExactly("🌾");
        var result=mvc.perform(post("/api/comments").contentType("application/json").content(valid)).andExpect(status().isCreated()).andReturn();
        assertThat(json.readTree(result.getResponse().getContentAsString()).path("data").size()).isEqualTo(5);
        verify(dao).insertTasteTags(9,List.of("🌾"));
    }

}
