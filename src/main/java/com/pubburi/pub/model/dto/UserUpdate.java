package com.pubburi.pub.model.dto;

/** Null fields retain the values read under the account update lock. */
public record UserUpdate(String name, String pass, Integer stamps, String role) {
}
