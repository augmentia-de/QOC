package de.augmentia.oc.engine.core.model;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.regex.Pattern;

public final class SkillId {

    private static final Pattern PATTERN = Pattern.compile("skl_[0-9a-f]{64}");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final String value;

    private SkillId(String value) {
        this.value = value;
    }

    public static SkillId create() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return new SkillId("skl_" + HexFormat.of().formatHex(bytes));
    }

    public static SkillId parse(String raw) {
        if (raw == null || !PATTERN.matcher(raw).matches()) {
            throw new IllegalArgumentException("Invalid skill id: " + raw);
        }
        return new SkillId(raw);
    }

    public static SkillId of(String raw) {
        return parse(raw);
    }

    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof SkillId s && s.value.equals(value);
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
