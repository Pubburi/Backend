package com.pubburi.pub.model.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class PasswordHasher {
	private static final String BCRYPT_PREFIX = "$2";
	private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

	public String hash(String raw) {
		if (raw == null) {
			return null;
		}
		return encoder.encode(raw);
	}

	public boolean matches(String raw, String hashed) {
		if (raw == null || hashed == null) {
			return false;
		}
		if (isBcrypt(hashed)) {
			return encoder.matches(raw, hashed);
		}
		return hashed.equals(legacySha256(raw));
	}

	public boolean needsUpgrade(String hashed) {
		return hashed != null && !isBcrypt(hashed);
	}

	String legacySha256(String raw) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] hashed = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
			StringBuilder builder = new StringBuilder();
			for (byte value : hashed) {
				builder.append(String.format("%02x", value));
			}
			return builder.toString();
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is not available", e);
		}
	}

	private boolean isBcrypt(String hashed) {
		return hashed.startsWith(BCRYPT_PREFIX);
	}
}
