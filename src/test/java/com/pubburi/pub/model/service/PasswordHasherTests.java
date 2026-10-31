package com.pubburi.pub.model.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PasswordHasherTests {
	private final PasswordHasher passwordHasher = new PasswordHasher();

	@Test
	void hashesAndVerifiesWithBcrypt() {
		String hashed = passwordHasher.hash("pass01");

		assertThat(hashed).startsWith("$2");
		assertThat(passwordHasher.matches("pass01", hashed)).isTrue();
		assertThat(passwordHasher.matches("wrong", hashed)).isFalse();
		assertThat(passwordHasher.needsUpgrade(hashed)).isFalse();
	}

	@Test
	void verifiesLegacySha256AndMarksForUpgrade() {
		String legacy = passwordHasher.legacySha256("pass01");

		assertThat(passwordHasher.matches("pass01", legacy)).isTrue();
		assertThat(passwordHasher.needsUpgrade(legacy)).isTrue();
	}
}
