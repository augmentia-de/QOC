package de.augmentia.oc.engine.core.model;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.regex.Pattern;

public final class MessageId {

    private static final Pattern PATTERN = Pattern.compile("msg_[0-9a-f]{64}");

    private final String value;

    private MessageId(String value) {
        this.value = value;
    }

    public static MessageId create() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return new MessageId("msg_" + HexFormat.of().formatHex(bytes));
    }

    public static MessageId parse(String raw) {
        if (raw == null || !PATTERN.matcher(raw).matches()) {
            throw new IllegalArgumentException("Invalid MessageId: " + raw);
        }
        return new MessageId(raw);
    }

    public static MessageId of(String raw) {
        return parse(raw);
    }

    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof MessageId s && s.value.equals(value);
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
