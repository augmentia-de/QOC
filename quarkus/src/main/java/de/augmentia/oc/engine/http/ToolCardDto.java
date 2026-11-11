package de.augmentia.oc.engine.http;

/** Tool card attached to a message. */
public record ToolCardDto(String id, String name, String input, String result, boolean failed) {}
