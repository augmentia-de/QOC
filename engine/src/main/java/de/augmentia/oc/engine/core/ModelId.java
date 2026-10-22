package de.augmentia.oc.engine.core;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.regex.Pattern;

public final class ModelId {

    private static final Pattern PATTERN = Pattern.compile("mdl_[0-9a-f]{64}");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final String value;

    private ModelId(String value) {
        this.value = value;
    }

    public static ModelId create() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return new ModelId("mdl_" + HexFormat.of().formatHex(bytes));
    }

    public static ModelId parse(String raw) {
        if (raw == null || !PATTERN.matcher(raw).matches()) {
            throw new IllegalArgumentException("Invalid ModelId: " + raw);
        }
        return new ModelId(raw);
    }

    public static ModelId of(String raw) {
        return parse(raw);
    }

    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ModelId s && s.value.equals(value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return value;
    }
}
