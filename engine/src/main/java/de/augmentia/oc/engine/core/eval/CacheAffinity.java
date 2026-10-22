package de.augmentia.oc.engine.core.eval;

import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.SessionId;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Prompt-cache affinity of provider/04 §6. The key is session-stable: a child
 * uses the parent's affinity, a fork uses the source session's affinity.
 */
public final class CacheAffinity {

    private static final Pattern SESSION_FORM = Pattern.compile("ses_[0-9a-f]{64}");

    public static final String HEADER_AFFINITY = "x-session-affinity";
    public static final String HEADER_SESSION_ID = "X-Session-Id";
    public static final String HEADER_OPENCODE = "x-opencode-session";

    private CacheAffinity() {}

    /** {@code parentID ?? forkSessionID ?? own id}, as a string value. */
    public static String affinity(SessionId sessionId, Option<SessionId> parentId,
                                  Option<SessionId> forkSourceId) {
        if (sessionId == null) throw new IllegalArgumentException("sessionId must not be null");
        if (parentId == null) parentId = Option.none();
        if (forkSourceId == null) forkSourceId = Option.none();
        if (parentId.isPresent()) return parentId.orElseThrow().value();
        if (forkSourceId.isPresent()) return forkSourceId.orElseThrow().value();
        return sessionId.value();
    }

    /** The three headers carry the same value for different gateways. */
    public static Map<String, String> headers(String affinity) {
        if (affinity == null || affinity.isBlank()) {
            throw new IllegalArgumentException("affinity must not be blank");
        }
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(HEADER_AFFINITY, affinity);
        headers.put(HEADER_SESSION_ID, affinity);
        headers.put(HEADER_OPENCODE, affinity);
        return Map.copyOf(headers);
    }

    /**
     * Provider-specific prompt cache key: the session-ID form loses its
     * {@code ses_} prefix; everything else is passed through unchanged.
     */
    public static String promptCacheKey(String affinity) {
        if (affinity == null) throw new IllegalArgumentException("affinity must not be null");
        if (SESSION_FORM.matcher(affinity).matches()) {
            return affinity.substring("ses_".length());
        }
        return affinity;
    }
}
