package de.augmentia.oc.engine.core.model;

public record InstructionBlob(String hash, String value) {
    public InstructionBlob {
        if (hash == null || hash.isBlank()) throw new IllegalArgumentException("hash must not be blank");
        if (value == null) value = "";
    }
}