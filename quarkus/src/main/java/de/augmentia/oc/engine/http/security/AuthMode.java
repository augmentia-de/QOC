package de.augmentia.oc.engine.http.security;

public enum AuthMode {
    NONE,
    BASIC,
    OIDC,
    PROXY,
    MCP_OAUTH;

    public static AuthMode from(String value) {
        if (value == null || value.isBlank()) {
            return NONE;
        }
        try {
            return AuthMode.valueOf(value.trim().toUpperCase().replace('-', '_'));
        } catch (IllegalArgumentException e) {
            return NONE;
        }
    }
}
