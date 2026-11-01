package com.pubburi.pub.controller.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.pubburi.pub.controller.api.ApiResponse;
import com.pubburi.pub.controller.api.GlobalExceptionHandler;
import com.pubburi.pub.controller.request.JoinUserRequest;

import jakarta.validation.Valid;

class JsonCharsetContractTests {
	private static final List<Charset> CHARSETS = List.of(StandardCharsets.UTF_8,
			StandardCharsets.ISO_8859_1, StandardCharsets.UTF_16);
	private final Fixture controller = new Fixture();
	private final MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
			.setControllerAdvice(new GlobalExceptionHandler()).build();

	@Test
	void normalBusinessDtoRemainsReadableAcrossSupportedCharsetPaths() throws Exception {
		String json = "{\"id\":\"fixture\",\"name\":\"Owned fixture\",\"pass\":\"fixture-only\"}";
		for (Charset charset : CHARSETS) {
			mvc.perform(post("/json-contract").contentType(new MediaType("application", "json", charset))
					.content(json.getBytes(charset))).andExpect(status().isOk())
					.andExpect(jsonPath("$.data").value("fixture"));
		}
		assertThat(controller.calls).isEqualTo(3);
	}

	@Test
	void oversizedFieldNamesUseSafe400BeforeControllerWritesForEveryCharset() throws Exception {
		String json = "{\"id\":\"fixture\",\"name\":\"Owned fixture\",\"pass\":\"fixture-only\",\"private-source-"
				+ "a".repeat(60000) + "\":null}";
		for (Charset charset : CHARSETS) {
			mvc.perform(post("/json-contract").contentType(new MediaType("application", "json", charset))
					.content(json.getBytes(charset))).andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
					.andExpect(jsonPath("$.error.message").value("Malformed request body"))
					.andExpect(content().string(not(containsString("private-source-"))));
		}
		assertThat(controller.calls).isZero();
	}

	@RestController
	static class Fixture {
		int calls;
		@PostMapping("/json-contract")
		ApiResponse<String> receive(@Valid @RequestBody JoinUserRequest request) {
			calls++;
			return ApiResponse.ok(request.id());
		}
	}
}
