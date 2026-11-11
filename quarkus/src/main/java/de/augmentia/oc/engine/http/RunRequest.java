package de.augmentia.oc.engine.http;

/** One-shot run payload. */
public record RunRequest(String agent, String prompt, String mode, Boolean retain) {}
