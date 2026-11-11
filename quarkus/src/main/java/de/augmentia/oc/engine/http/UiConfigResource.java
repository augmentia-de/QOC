package de.augmentia.oc.engine.http;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;

/**
 * Serves the runtime configuration for the browser UI as {@code /config.js}.
 *
 * <p>The UI is OIDC-free by default (local development, no login). Docker and
 * cloud deployments enable OIDC with the {@code ENGINE_UI_OIDC_*} environment
 * variables without rebuilding the image:
 *
 * <ul>
 *   <li>{@code ENGINE_UI_OIDC_ENABLED} – {@code true} to show the login button</li>
 *   <li>{@code ENGINE_UI_OIDC_ISSUER} – OIDC issuer URL visible to the browser
 *       (for the local Keycloak: {@code http://localhost:8180/realms/qoc})</li>
 *   <li>{@code ENGINE_UI_OIDC_CLIENT_ID} – public PKCE client (default {@code qoc-ui})</li>
 *   <li>{@code ENGINE_UI_OIDC_AUDIENCE} – optional API audience (Auth0)</li>
 *   <li>{@code ENGINE_UI_OIDC_API_SCOPE} – optional API scope (Auth0)</li>
 * </ul>
 *
 * <p>All values can also be set as system properties, e.g.
 * {@code -Dengine.ui.oidc.issuer=...}. Environment variables win.
 */
@Path("/config.js")
@ApplicationScoped
public class UiConfigResource {

    @GET
    @Produces("application/javascript")
    public String config() {
        StringBuilder json = new StringBuilder("{");
        json.append("\"oidcEnabled\":")
                .append(flag("ENGINE_UI_OIDC_ENABLED", "engine.ui.oidc.enabled", false));
        appendString(json, "issuer", text("ENGINE_UI_OIDC_ISSUER", "engine.ui.oidc.issuer", ""));
        appendString(json, "clientId", text("ENGINE_UI_OIDC_CLIENT_ID", "engine.ui.oidc.client-id", "qoc-ui"));
        appendString(json, "audience", text("ENGINE_UI_OIDC_AUDIENCE", "engine.ui.oidc.audience", ""));
        appendString(json, "apiScope", text("ENGINE_UI_OIDC_API_SCOPE", "engine.ui.oidc.api-scope", ""));
        json.append('}');
        return "window.QOC_AUTH = " + json + ";\n";
    }

    private static boolean flag(String envName, String propertyName, boolean fallback) {
        String value = text(envName, propertyName, null);
        if (value == null) return fallback;
        return "true".equalsIgnoreCase(value) || "1".equals(value) || "yes".equalsIgnoreCase(value);
    }

    private static String text(String envName, String propertyName, String fallback) {
        String value = System.getenv(envName);
        if (value == null || value.isBlank()) {
            value = System.getProperty(propertyName);
        }
        return value == null || value.isBlank() ? fallback : value;
    }

    private static void appendString(StringBuilder json, String key, String value) {
        if (value == null || value.isBlank()) return;
        json.append(",\"").append(key).append("\":\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                case '\n' -> json.append("\\n");
                case '\r' -> json.append("\\r");
                default -> json.append(c);
            }
        }
        json.append('"');
    }
}
