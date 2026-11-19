package de.augmentia.oc.engine.core.model;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * Opaque session identifier. Not convertible to any other ID type.
 */
public final class SessionId {

    /**
     * Current session IDs use 16 or 32 random bytes. A child ID carries its
     * parent ID followed by the child suffix, e.g.
     * {@code ses_ses_<parentHex>_<childHex>}. Keep the parser aligned with
     * the factories below.
     */
    private static final Pattern PATTERN =
            Pattern.compile("ses_(?:ses_)*[0-9a-f]+(?:_[0-9a-f]+)*");

    private final String value;

    private SessionId(String value) {
        this.value = value;
    }

    public static SessionId create() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        return new SessionId("ses_" + HexFormat.of().formatHex(bytes));
    }

    public static SessionId create(SessionId parent) {
        if (parent == null) {
            return create();
        }
        byte[] bytes = new byte[8];
        new SecureRandom().nextBytes(bytes);
        return new SessionId("ses_" + parent.value + "_" + HexFormat.of().formatHex(bytes));
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

    /**
     * True when this ID was created with {@link #create(SessionId)} and
     * therefore carries its parent ID.
     */
    public boolean isChild() {
        return parent().isPresent();
    }

    /**
     * Returns the direct parent encoded in a child ID. Top-level IDs and
     * malformed child IDs return {@link Option#none()}.
     */
    public Option<SessionId> parent() {
        if (!value.startsWith("ses_ses_")) {
            return Option.none();
        }
        int separator = value.lastIndexOf('_');
        if (separator <= 4) {
            return Option.none();
        }
        try {
            return Option.some(parse(value.substring(4, separator)));
        } catch (IllegalArgumentException e) {
            return Option.none();
        }
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