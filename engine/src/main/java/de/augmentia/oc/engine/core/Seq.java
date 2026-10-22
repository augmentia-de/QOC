package de.augmentia.oc.engine.core;

public record Seq(long value) {
    public Seq {
        if (value < 0) {
            throw new IllegalArgumentException("Seq must not be negative: " + value);
        }
    }

    public static Seq of(long value) {
        return new Seq(value);
    }

    public static Seq zero() {
        return new Seq(0);
    }
}
