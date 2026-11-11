package de.augmentia.oc.engine.http;

/** Session creation payload. */
public record CreateSessionRequest(String agent, String mode, Boolean retain) {}
