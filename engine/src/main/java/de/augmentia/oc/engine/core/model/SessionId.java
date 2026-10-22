package de.augmentia.oc.engine.core.model;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * Opaque session identifier. Not convertible to any other ID type.
 */
public final class SessionId {

    private static final Pattern PATTERN = Pattern.compile("ses_[0-9a-f]{64}");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final String value;

    private SessionId(String value) {
        this.value = value;
    }

    public static SessionId create() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return new SessionId("ses_" + HexFormat.of().formatHex(bytes));
    }

    public static SessionId parse(String raw) {
        if (raw == null || !PATTERN.matcher(raw).matches()) {
            throw new IllegalArgumentException("Invalid session id: " + raw);
        }
        return new SessionId(raw);
    }

    public static SessionId of(String raw) {
        return parse(raw);
    }

    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof SessionId s && s.value.equals(value);
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