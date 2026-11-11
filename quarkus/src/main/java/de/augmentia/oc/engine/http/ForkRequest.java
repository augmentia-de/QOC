package de.augmentia.oc.engine.http;

/** Session fork payload. */
public record ForkRequest(String boundary, String directory, String agent) {}
