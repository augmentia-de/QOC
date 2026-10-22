package de.augmentia.oc.engine.core.model;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.regex.Pattern;

public final class InboxId {

    private static final Pattern PATTERN = Pattern.compile("inb_[0-9a-f]{64}");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final String value;

    private InboxId(String value) {
        this.value = value;
    }

    public static InboxId create() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return new InboxId("inb_" + HexFormat.of().formatHex(bytes));
    }

    public static InboxId parse(String raw) {
        if (raw == null || !PATTERN.matcher(raw).matches()) {
            throw new IllegalArgumentException("Invalid InboxId: " + raw);
        }
        return new InboxId(raw);
    }

    public static InboxId of(String raw) {
        return parse(raw);
    }

    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof InboxId s && s.value.equals(value);
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
