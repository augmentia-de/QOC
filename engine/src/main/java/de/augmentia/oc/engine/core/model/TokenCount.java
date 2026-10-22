package de.augmentia.oc.engine.core.model;

public record TokenCount(long input, long output) {
    public TokenCount {
        if (input < 0 || output < 0) {
            throw new IllegalArgumentException("Token counts must not be negative");
        }
    }

    public long total() {
        return input + output;
    }

    public static TokenCount zero() {
        return new TokenCount(0, 0);
    }

    public static TokenCount of(long input, long output) {
        return new TokenCount(input, output);
    }
}