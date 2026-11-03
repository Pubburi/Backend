package com.pubburi.pub.controller.rest;

import static org.assertj.core.api.Assertions.assertThat;
import java.lang.reflect.Proxy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.pubburi.pub.controller.api.GlobalExceptionHandler;
import com.pubburi.pub.controller.auth.*;
import com.pubburi.pub.model.service.UserService;
import com.pubburi.pub.model.dto.UserUpdateResult;

class SelfAccountContractTests {
    static final String PROFILE = "/api/users/me/profile", PASSWORD = "/api/users/me/password";
    final List<String> calls = new ArrayList<>();
    boolean profileResult;
    boolean wrongPassword;
    UserUpdateResult passwordResult;
    final UserService users = (UserService) Proxy.newProxyInstance(UserService.class.getClassLoader(), new Class<?>[] {UserService.class},
            (proxy, method, args) -> {
                calls.add(method.getName() + Arrays.toString(args));
                if (method.getName().equals("modifyOwnProfile")) return profileResult;
                if (method.getName().equals("modifyOwnPassword")) {
                    if (wrongPassword) throw new UserService.CurrentPasswordMismatchException();
                    return passwordResult;
                }
                throw new AssertionError(method.getName());
            });
    final AccessGuard guard = SessionFixtures.guard(SessionFixtures.user("member", "USER"));
    final MockHttpSession session = new MockHttpSession();
    final MockMvc mvc = MockMvcBuilders.standaloneSetup(new UserRestController(users, null, guard, null))
            .setControllerAdvice(new GlobalExceptionHandler())
            .setMessageConverters(new MappingJackson2HttpMessageConverter(Jackson2ObjectMapperBuilder.json()
                    .featuresToDisable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build())).build();
    SelfAccountContractTests() { guard.establishSession(session, SessionFixtures.user("member", "USER")); }

    @Test void validSelfInputUsesOnlySessionIdentityAndKeepsLiteralStrings() throws Exception {
        profileResult = true;
        mvc.perform(put(PROFILE).session(session).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\" literal name \"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").value(true));
        passwordResult = new UserUpdateResult("member", "fixture-member", "next-epoch", true);
        mvc.perform(put(PASSWORD).session(session).contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\" old pass \",\"newPassword\":\" new pass \"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").value(true));
        assertThat(((SessionUser) session.getAttribute(AccessGuard.SESSION_USER)).authEpoch()).isEqualTo("next-epoch");
        assertThat(calls).containsExactly("modifyOwnProfile[member, fixture-member,  literal name ]", "modifyOwnPassword[member, fixture-member,  old pass ,  new pass ]");
    }

    @Test void malformedTypesUnknownFieldsMissingBlankAndLengthsFailBeforeService() throws Exception {
        for (String body : List.of("{}", "null", "[]", "{\"name\":null}", "{\"name\":1}", "{\"name\":true}", "{\"name\":{}}", "{\"name\":\" \"}",
                "{\"name\":\"" + "a".repeat(101) + "\"}", "{\"name\":\"valid\",\"id\":\"other\"}", "{\"name\":\"valid\",\"role\":\"ADMIN\"}", "{\"name\":\"valid\",\"unknown\":null}")) {
            mvc.perform(put(PROFILE).session(session).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        }
        for (String body : List.of("{}", "null", "{\"currentPassword\":\"old pass\"}", "{\"currentPassword\":null,\"newPassword\":\"new pass\"}",
                "{\"currentPassword\":1,\"newPassword\":\"new pass\"}", "{\"currentPassword\":\"old pass\",\"newPassword\":false}",
                "{\"currentPassword\":\"old pass\",\"newPassword\":\"   \"}", "{\"currentPassword\":\"old pass\",\"newPassword\":\"abc\"}",
                "{\"currentPassword\":\"" + "a".repeat(101) + "\",\"newPassword\":\"new pass\"}",
                "{\"currentPassword\":\"old pass\",\"newPassword\":\"new pass\",\"authEpoch\":\"forged\"}")) {
            mvc.perform(put(PASSWORD).session(session).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        }
        assertThat(calls).isEmpty();
    }

    @Test void querySelectorsAreRejectedAndWrongCurrentPasswordKeepsTheLiveSession() throws Exception {
        mvc.perform(put(PROFILE + "?id=other").session(session).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"valid\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put(PASSWORD + "?role=ADMIN").session(session).contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"wrong\",\"newPassword\":\"new pass\"}")).andExpect(status().isBadRequest());
        wrongPassword = true;
        mvc.perform(put(PASSWORD).session(session).contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"wrong\",\"newPassword\":\"new pass\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        assertThat(guard.requireLogin(session).id()).isEqualTo("member");
        assertThat(calls).containsExactly("modifyOwnPassword[member, fixture-member, wrong, new pass]");
    }

    @Test void newPasswordByteLimitRejectsOverflowBeforeTheServiceAndKeepsShortCurrentInputValid() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        for (String raw : List.of("a".repeat(73), "가".repeat(25), "😀".repeat(19))) {
            mvc.perform(put(PASSWORD).session(session).contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(Map.of("currentPassword", "x", "newPassword", raw))))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.fields.newPassword").exists());
        }
        assertThat(calls).isEmpty();
        passwordResult = new UserUpdateResult("member", "fixture-member", "next", true);
        mvc.perform(put(PASSWORD).session(session).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("currentPassword", "x", "newPassword", "가".repeat(24)))))
                .andExpect(status().isOk());
        assertThat(calls).hasSize(1);
    }

    @Test void anonymousAndIdentityLostAfterGuardAre401() throws Exception {
        mvc.perform(put(PROFILE).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"valid\"}")).andExpect(status().isUnauthorized());
        mvc.perform(put(PASSWORD).contentType(MediaType.APPLICATION_JSON).content("{\"currentPassword\":\"old pass\",\"newPassword\":\"new pass\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(put(PROFILE).session(session).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"valid\"}")).andExpect(status().isUnauthorized());
        mvc.perform(put(PASSWORD).session(session).contentType(MediaType.APPLICATION_JSON).content("{\"currentPassword\":\"old pass\",\"newPassword\":\"new pass\"}"))
                .andExpect(status().isUnauthorized());
        assertThat(((SessionUser) session.getAttribute(AccessGuard.SESSION_USER)).authEpoch()).isEqualTo("fixture-member");
    }
}
