package de.augmentia.oc.engine.runtime.tool.mcp;

import de.augmentia.oc.engine.core.json.Json;
import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.json.Result;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.port.world.HttpTransport;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal OAuth 2.1 helpers for remote MCP servers: metadata discovery,
 * authorization URL construction and code exchange. Token refresh is optional.
 */
public final class McpOAuth {

    public record OAuthMetadata(String authorizationEndpoint, String tokenEndpoint,
                                Option<String> registrationEndpoint,
                                List<String> scopes) {
        public OAuthMetadata {
            if (authorizationEndpoint == null || authorizationEndpoint.isBlank())
                throw new IllegalArgumentException("authorizationEndpoint must not be blank");
            if (tokenEndpoint == null || tokenEndpoint.isBlank())
                throw new IllegalArgumentException("tokenEndpoint must not be blank");
            if (registrationEndpoint == null) registrationEndpoint = Option.none();
            scopes = scopes == null ? List.of() : List.copyOf(scopes);
        }
    }

    public record OAuthToken(String accessToken, Option<String> refreshToken, Option<Instant> expiresAt) {
        public OAuthToken {
            if (accessToken == null || accessToken.isBlank())
                throw new IllegalArgumentException("accessToken must not be blank");
            if (refreshToken == null) refreshToken = Option.none();
            if (expiresAt == null) expiresAt = Option.none();
        }
    }

    private McpOAuth() {}

    /** Discovers metadata from {@code /.well-known/oauth-authorization-server}. */
    public static OAuthMetadata discover(HttpTransport transport, String serverUrl) {
        if (transport == null || serverUrl == null || serverUrl.isBlank())
            throw new IllegalArgumentException("transport and serverUrl must be set");
        URI base = URI.create(serverUrl);
        String metadataUrl = base.getScheme() + "://" + base.getHost()
                + (base.getPort() > 0 ? ":" + base.getPort() : "")
                + "/.well-known/oauth-authorization-server";
        try {
            HttpTransport.ResponseStream response = transport.postJson(
                    URI.create(metadataUrl), Map.of(), "", Duration.ofSeconds(10));
            String text;
            try { text = new String(response.body().readAllBytes(), StandardCharsets.UTF_8); }
            catch (java.io.IOException e) { throw new McpFailure("cannot read response", e); }
            response.close();
            JsonValue parsed = Json.parse(text).valueOrNull();
            if (!(parsed instanceof JsonValue.Obj obj)) throw new McpFailure("invalid oauth metadata");
            String auth = string(obj, "authorization_endpoint");
            String token = string(obj, "token_endpoint");
            if (auth == null || token == null) throw new McpFailure("missing oauth endpoints");
            Option<String> registration = Option.of(string(obj, "registration_endpoint"));
            List<String> scopes = List.of();
            if (obj.values().get("scopes_supported") instanceof JsonValue.Arr arr) {
                scopes = arr.values().stream()
                        .filter(JsonValue.Str.class::isInstance)
                        .map(v -> ((JsonValue.Str) v).value())
                        .toList();
            }
            return new OAuthMetadata(auth, token, registration, scopes);
        } catch (RuntimeException e) {
            throw new McpFailure("oauth discovery failed: " + e.getMessage(), e);
        }
    }

    /** Builds the authorization URL with PKCE (S256). */
    public static String authorizationUrl(OAuthMetadata metadata, String clientId,
                                          String redirectUri, String state, String codeChallenge,
                                          List<String> scopes) {
        StringBuilder url = new StringBuilder(metadata.authorizationEndpoint());
        url.append("?response_type=code");
        url.append("&client_id=").append(encode(clientId));
        url.append("&redirect_uri=").append(encode(redirectUri));
        if (scopes != null && !scopes.isEmpty()) url.append("&scope=").append(encode(String.join(" ", scopes)));
        if (state != null) url.append("&state=").append(encode(state));
        if (codeChallenge != null) {
            url.append("&code_challenge=").append(encode(codeChallenge));
            url.append("&code_challenge_method=S256");
        }
        return url.toString();
    }

    /** Exchanges an authorization code for an access token. */
    public static OAuthToken exchangeCode(HttpTransport transport, String tokenEndpoint,
                                          String clientId, String clientSecret, String code,
                                          String redirectUri, String codeVerifier) {
        if (transport == null || tokenEndpoint == null || clientId == null || code == null
                || redirectUri == null) throw new IllegalArgumentException("token exchange inputs must not be null");
        StringBuilder body = new StringBuilder();
        body.append("grant_type=authorization_code");
        body.append("&code=").append(encode(code));
        body.append("&redirect_uri=").append(encode(redirectUri));
        body.append("&client_id=").append(encode(clientId));
        if (clientSecret != null && !clientSecret.isBlank()) {
            body.append("&client_secret=").append(encode(clientSecret));
        }
        if (codeVerifier != null && !codeVerifier.isBlank()) {
            body.append("&code_verifier=").append(encode(codeVerifier));
        }
        try {
            HttpTransport.ResponseStream response = transport.postJson(
                    URI.create(tokenEndpoint),
                    Map.of("content-type", "application/x-www-form-urlencoded"),
                    body.toString(), Duration.ofSeconds(15));
            String text;
            try { text = new String(response.body().readAllBytes(), StandardCharsets.UTF_8); }
            catch (java.io.IOException e) { throw new McpFailure("cannot read response", e); }
            response.close();
            JsonValue parsed = Json.parse(text).valueOrNull();
            if (!(parsed instanceof JsonValue.Obj obj)) throw new McpFailure("invalid token response");
            String accessToken = string(obj, "access_token");
            if (accessToken == null) throw new McpFailure("missing access_token");
            Option<String> refresh = Option.of(string(obj, "refresh_token"));
            Option<Instant> expires = Option.none();
            if (obj.values().get("expires_in") instanceof JsonValue.Num num) {
                expires = Option.some(Instant.now().plusSeconds(num.value().longValue()));
            }
            return new OAuthToken(accessToken, refresh, expires);
        } catch (RuntimeException e) {
            throw new McpFailure("token exchange failed: " + e.getMessage(), e);
        }
    }

    /** Refreshes an access token. */
    public static OAuthToken refreshToken(HttpTransport transport, String tokenEndpoint,
                                         String clientId, String clientSecret, String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new McpFailure("no refresh token available");
        }
        StringBuilder body = new StringBuilder();
        body.append("grant_type=refresh_token");
        body.append("&refresh_token=").append(encode(refreshToken));
        body.append("&client_id=").append(encode(clientId));
        if (clientSecret != null && !clientSecret.isBlank()) {
            body.append("&client_secret=").append(encode(clientSecret));
        }
        try {
            HttpTransport.ResponseStream response = transport.postJson(
                    URI.create(tokenEndpoint),
                    Map.of("content-type", "application/x-www-form-urlencoded"),
                    body.toString(), Duration.ofSeconds(15));
            String text;
            try { text = new String(response.body().readAllBytes(), StandardCharsets.UTF_8); }
            catch (java.io.IOException e) { throw new McpFailure("cannot read response", e); }
            response.close();
            JsonValue parsed = Json.parse(text).valueOrNull();
            if (!(parsed instanceof JsonValue.Obj obj)) throw new McpFailure("invalid token response");
            String accessToken = string(obj, "access_token");
            if (accessToken == null) throw new McpFailure("missing access_token");
            Option<String> refresh = Option.of(string(obj, "refresh_token"));
            if (refresh.isEmpty()) refresh = Option.some(refreshToken);
            Option<Instant> expires = Option.none();
            if (obj.values().get("expires_in") instanceof JsonValue.Num num) {
                expires = Option.some(Instant.now().plusSeconds(num.value().longValue()));
            }
            return new OAuthToken(accessToken, refresh, expires);
        } catch (RuntimeException e) {
            throw new McpFailure("token refresh failed: " + e.getMessage(), e);
        }
    }

    private static String string(JsonValue.Obj object, String key) {
        return object.values().get(key) instanceof JsonValue.Str str ? str.value() : null;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
