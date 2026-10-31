package com.pubburi.pub.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import org.apache.tomcat.util.http.fileupload.MultipartStream;
import org.apache.tomcat.util.http.fileupload.impl.FileUploadIOException;
import org.apache.tomcat.util.http.fileupload.impl.SizeLimitExceededException;
import org.junit.jupiter.api.Test;

class MultipartParserSecurityTests {

	@Test
	void normalPartHeadersRemainReadable() throws Exception {
		String header = "Content-Disposition: form-data; name=\"fixture\"\r\n\r\n";
		assertThat(stream(header).readHeaders()).isEqualTo(header);
	}

	@Test
	void oversizedPartHeadersAreRejectedBeforeTheyCanAccumulate() throws Exception {
		String header = "Content-Disposition: form-data; name=\"fixture\"\r\nX-Fixture: "
				+ "a".repeat(2048) + "\r\n\r\n";
		assertThatThrownBy(() -> stream(header).readHeaders())
				.isInstanceOf(FileUploadIOException.class)
				.hasCauseInstanceOf(SizeLimitExceededException.class)
				.satisfies(error -> assertThat(((SizeLimitExceededException) error.getCause()).getPermittedSize())
						.isEqualTo(512));
	}

	private MultipartStream stream(String header) throws Exception {
		return new MultipartStream(new ByteArrayInputStream(header.getBytes(StandardCharsets.US_ASCII)),
				"fixture-boundary".getBytes(StandardCharsets.US_ASCII), 4096, null);
	}
}
