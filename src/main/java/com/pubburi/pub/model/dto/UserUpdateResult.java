package com.pubburi.pub.model.dto;

/** Identity versions captured by this update while the account row is locked. */
public record UserUpdateResult(String id, String previousAuthEpoch, String authEpoch, boolean credentialsChanged) {
}
