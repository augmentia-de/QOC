package de.augmentia.oc.engine.core.model;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.regex.Pattern;

/** Opaque event-log identifier. Not convertible to any other ID type. */
public final class EventId {

    private static final Pattern PATTERN = Pattern.compile("evt_[0-9a-f]{64}");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final String value;

    private EventId(String value) {
        this.value = value;
    }

    public static EventId create() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return new EventId("evt_" + HexFormat.of().formatHex(bytes));
    }

    public static EventId parse(String raw) {
        if (raw == null || !PATTERN.matcher(raw).matches()) {
            throw new IllegalArgumentException("Invalid event id: " + raw);
        }
        return new EventId(raw);
    }

    public static EventId of(String raw) {
        return parse(raw);
    }

    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof EventId e && e.value.equals(value);
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
