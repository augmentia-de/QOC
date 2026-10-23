package de.augmentia.oc.engine.core.model;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.regex.Pattern;

public final class ProviderId {

    private static final Pattern PATTERN = Pattern.compile("prv_[0-9a-f]{64}");

    private final String value;

    private ProviderId(String value) {
        this.value = value;
    }

    public static ProviderId create() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return new ProviderId("prv_" + HexFormat.of().formatHex(bytes));
    }

    public static ProviderId parse(String raw) {
        if (raw == null || !PATTERN.matcher(raw).matches()) {
            throw new IllegalArgumentException("Invalid ProviderId: " + raw);
        }
        return new ProviderId(raw);
    }

    public static ProviderId of(String raw) {
        return parse(raw);
    }

    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ProviderId s && s.value.equals(value);
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
