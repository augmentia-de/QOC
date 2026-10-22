package de.augmentia.oc.engine.core;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.regex.Pattern;

public final class SnapshotId {

    private static final Pattern PATTERN = Pattern.compile("snp_[0-9a-f]{64}");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final String value;

    private SnapshotId(String value) {
        this.value = value;
    }

    public static SnapshotId create() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return new SnapshotId("snp_" + HexFormat.of().formatHex(bytes));
    }

    public static SnapshotId parse(String raw) {
        if (raw == null || !PATTERN.matcher(raw).matches()) {
            throw new IllegalArgumentException("Invalid SnapshotId: " + raw);
        }
        return new SnapshotId(raw);
    }

    public static SnapshotId of(String raw) {
        return parse(raw);
    }

    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof SnapshotId s && s.value.equals(value);
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
