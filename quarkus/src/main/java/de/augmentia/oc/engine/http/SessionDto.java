package de.augmentia.oc.engine.http;

/** Session summary exposed by the REST API. */
public record SessionDto(String id, String title, String agent, String directory,
                         String cost, long tokens) {}
