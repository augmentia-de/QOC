package de.augmentia.oc.engine.core;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.regex.Pattern;

public final class ProjectId {

    private static final Pattern PATTERN = Pattern.compile("prj_[0-9a-f]{64}");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final String value;

    private ProjectId(String value) {
        this.value = value;
    }

    public static ProjectId create() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return new ProjectId("prj_" + HexFormat.of().formatHex(bytes));
    }

    public static ProjectId parse(String raw) {
        if (raw == null || !PATTERN.matcher(raw).matches()) {
            throw new IllegalArgumentException("Invalid ProjectId: " + raw);
        }
        return new ProjectId(raw);
    }

    public static ProjectId of(String raw) {
        return parse(raw);
    }

    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ProjectId s && s.value.equals(value);
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
