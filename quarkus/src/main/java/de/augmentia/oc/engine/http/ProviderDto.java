package de.augmentia.oc.engine.http;

/** Provider entry exposed by the catalogue API. */
public record ProviderDto(String id, String name, boolean available) {}
