package de.augmentia.oc.engine.core.model;

public record InstructionEntry(String key, String value, String hash) {
    public InstructionEntry {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("key must not be blank");
        if (value == null) value = "";
        if (hash == null) hash = "";
    }
}