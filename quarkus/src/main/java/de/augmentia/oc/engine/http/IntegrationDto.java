package de.augmentia.oc.engine.http;

/** Integration entry exposed by the catalogue API. */
public record IntegrationDto(String id, String name, boolean connected) {}
