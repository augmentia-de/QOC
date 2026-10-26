package de.augmentia.oc.engine.core.model;

/**
 * Session-scoped reference to stored tool output. The id is unique within the
 * session; {@link #uri()} is the only stable handle the model receives.
 */
public record ArtifactRef(SessionId session, String id, String mediaType, String sha256, long size) {

    public static final String SCHEME = "artifact://";

    public ArtifactRef {
        if (session == null) throw new IllegalArgumentException("session must not be null");
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id must not be blank");
        if (mediaType == null || mediaType.isBlank()) mediaType = "application/octet-stream";
        if (sha256 == null || sha256.isBlank()) throw new IllegalArgumentException("sha256 must not be blank");
        if (size < 0) throw new IllegalArgumentException("size must not be negative");
    }

    public String uri() {
        return SCHEME + session.value() + "/" + id;
    }

    /** Parses {@code artifact://<session>/<id>} and validates the session prefix. */
    public static Parsed parse(String uri) {
        if (uri == null || !uri.startsWith(SCHEME)) {
            throw new IllegalArgumentException("not an artifact URI: " + uri);
        }
        String rest = uri.substring(SCHEME.length());
        int slash = rest.indexOf('/');
        if (slash <= 0 || slash == rest.length() - 1) {
            throw new IllegalArgumentException("invalid artifact URI: " + uri);
        }
        SessionId session = SessionId.parse(rest.substring(0, slash));
        String id = rest.substring(slash + 1);
        if (id.contains("/")) throw new IllegalArgumentException("invalid artifact id: " + id);
        return new Parsed(session, id);
    }

    public record Parsed(SessionId session, String id) {}
}
