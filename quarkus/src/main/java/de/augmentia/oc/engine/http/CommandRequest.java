package de.augmentia.oc.engine.http;

/** Slash-command payload. */
public record CommandRequest(String name, String arguments) {}
