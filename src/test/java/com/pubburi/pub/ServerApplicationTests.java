package com.pubburi.pub;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import org.junit.jupiter.api.Test;

class ServerApplicationTests {

	@Test
	void applicationEntrypointIsLoadable() {
		assertDoesNotThrow(() -> Class.forName(ServerApplication.class.getName()));
	}
}
