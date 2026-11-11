package de.augmentia.oc.engine.core.port.world;

import de.augmentia.oc.engine.core.model.Principal;

/**
 * Issues short-lived, origin-bearing tokens for downstream tool calls.
 *
 * <p>Implementations must never echo the raw OIDC access token. The issued token
 * carries only the configured origin claims and expires quickly.</p>
 */
public interface ToolTokenService {

    /** Issues a token for the given principal/tool/session. Returns null if disabled. */
    String issue(Principal principal, String toolName, String sessionId, java.util.List<String> scopes, long ttlSeconds);

    /** Validates a previously issued token and returns its principal, or null if invalid/expired. */
    Principal validate(String token);

    /** No-op implementation used when token issuance is disabled. */
    static ToolTokenService disabled() {
        return new ToolTokenService() {
            @Override public String issue(Principal principal, String toolName, String sessionId, java.util.List<String> scopes, long ttlSeconds) { return null; }
            @Override public Principal validate(String token) { return null; }
        };
    }
}
