package de.augmentia.oc.engine.core.model;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.regex.Pattern;

public final class PermissionRequestId {

    private static final Pattern PATTERN = Pattern.compile("prm_[0-9a-f]{64}");

    private final String value;

    private PermissionRequestId(String value) {
        this.value = value;
    }

    public static PermissionRequestId create() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return new PermissionRequestId("prm_" + HexFormat.of().formatHex(bytes));
    }

    public static PermissionRequestId parse(String raw) {
        if (raw == null || !PATTERN.matcher(raw).matches()) {
            throw new IllegalArgumentException("Invalid PermissionRequestId: " + raw);
        }
        return new PermissionRequestId(raw);
    }

    public static PermissionRequestId of(String raw) {
        return parse(raw);
    }

    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof PermissionRequestId s && s.value.equals(value);
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
