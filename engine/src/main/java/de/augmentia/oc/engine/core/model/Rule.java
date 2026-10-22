package de.augmentia.oc.engine.core.model;

public record Rule(String action, String resource, Effect effect) {
    public Rule {
        if (action == null || action.isBlank()) throw new IllegalArgumentException("rule action must not be blank");
        if (resource == null || resource.isBlank()) throw new IllegalArgumentException("rule resource must not be blank");
        if (effect == null) throw new IllegalArgumentException("rule effect must not be null");
    }
}