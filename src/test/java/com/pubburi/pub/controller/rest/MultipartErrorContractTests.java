package com.pubburi.pub.controller.rest;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;

import com.pubburi.pub.controller.api.GlobalExceptionHandler;

class MultipartErrorContractTests {
	private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new FailingMultipartController())
			.setControllerAdvice(new GlobalExceptionHandler()).build();

	@Test
	void malformedMultipartUsesClientErrorWithoutParserDetails() throws Exception {
		mvc.perform(get("/multipart-contract/invalid").accept(MediaType.TEXT_PLAIN)).andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
				.andExpect(jsonPath("$.data").isEmpty())
				.andExpect(jsonPath("$.error.code").value("BAD_MULTIPART_REQUEST"))
				.andExpect(jsonPath("$.error.message").value("Malformed multipart request"))
				.andExpect(content().string(not(containsString("private-parser-detail"))));
	}

	@Test
	void uploadSizeSubtypeKeeps413InsteadOfBeingCollapsedInto400() throws Exception {
		mvc.perform(get("/multipart-contract/oversize").accept(MediaType.TEXT_PLAIN)).andExpect(status().isPayloadTooLarge())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
				.andExpect(jsonPath("$.error.code").value("PAYLOAD_TOO_LARGE"))
				.andExpect(jsonPath("$.error.message").value("Multipart request is too large"))
				.andExpect(content().string(not(containsString("private-parser-detail"))));
	}

	@Test
	void validUnsupportedMediaRemains415() throws Exception {
		mvc.perform(post("/multipart-contract/json-only").contentType(MediaType.MULTIPART_FORM_DATA))
				.andExpect(status().isUnsupportedMediaType())
				.andExpect(jsonPath("$.error.code").value("UNSUPPORTED_MEDIA_TYPE"));
	}

	@RestController
	static class FailingMultipartController {
		@GetMapping("/multipart-contract/invalid")
		void invalid() { throw new MultipartException("private-parser-detail"); }
		@GetMapping("/multipart-contract/oversize")
		void oversize() {
			throw new MaxUploadSizeExceededException(1024, new IllegalStateException("private-parser-detail"));
		}
		@PostMapping(value = "/multipart-contract/json-only", consumes = MediaType.APPLICATION_JSON_VALUE)
		void jsonOnly() { }
	}
}
