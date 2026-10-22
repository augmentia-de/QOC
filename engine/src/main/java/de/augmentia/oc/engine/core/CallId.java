package de.augmentia.oc.engine.core;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.regex.Pattern;

public final class CallId {

    private static final Pattern PATTERN = Pattern.compile("call_[0-9a-f]{64}");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final String value;

    private CallId(String value) {
        this.value = value;
    }

    public static CallId create() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return new CallId("call_" + HexFormat.of().formatHex(bytes));
    }

    public static CallId parse(String raw) {
        if (raw == null || !PATTERN.matcher(raw).matches()) {
            throw new IllegalArgumentException("Invalid CallId: " + raw);
        }
        return new CallId(raw);
    }

    public static CallId of(String raw) {
        return parse(raw);
    }

    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof CallId s && s.value.equals(value);
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
