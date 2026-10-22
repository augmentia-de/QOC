package de.augmentia.oc.engine.core.model;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.regex.Pattern;

public final class AgentId {

    private static final Pattern PATTERN = Pattern.compile("agt_[0-9a-f]{64}");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final String value;

    private AgentId(String value) {
        this.value = value;
    }

    public static AgentId create() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return new AgentId("agt_" + HexFormat.of().formatHex(bytes));
    }

    public static AgentId parse(String raw) {
        if (raw == null || !PATTERN.matcher(raw).matches()) {
            throw new IllegalArgumentException("Invalid AgentId: " + raw);
        }
        return new AgentId(raw);
    }

    public static AgentId of(String raw) {
        return parse(raw);
    }

    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof AgentId s && s.value.equals(value);
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
