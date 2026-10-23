package de.augmentia.oc.engine.runtime.tool.mcp;

import de.augmentia.oc.engine.core.json.Json;
import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.json.Result;
import de.augmentia.oc.engine.core.model.Credential;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.port.store.CredentialStore;
import de.augmentia.oc.engine.core.port.world.HttpTransport;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Remote MCP client: JSON-RPC over HTTP with tool discovery and OAuth. */
public final class McpDiscoveryClient implements McpClient {

    private final HttpTransport transport;
    private final McpServerConfig.Remote config;
    private final Option<CredentialStore> credentials;
    private final String integrationId;
    private volatile List<McpRegistrar.McpToolSpec> discovered;
    private volatile String sessionId;

    public McpDiscoveryClient(HttpTransport transport, McpServerConfig.Remote config) {
        this(transport, config, Option.none(), "mcp:" + config.name());
    }

    public McpDiscoveryClient(HttpTransport transport, McpServerConfig.Remote config,
                              Option<CredentialStore> credentials, String integrationId) {
        if (transport == null || config == null) throw new IllegalArgumentException("transport and config must not be null");
        this.transport = transport;
        this.config = config;
        this.credentials = credentials == null ? Option.none() : credentials;
        this.integrationId = (integrationId == null || integrationId.isBlank())
                ? "mcp:" + config.name() : integrationId;
    }

    @Override
    public JsonValue call(String toolName, JsonValue arguments) {
        if (toolName == null || toolName.isBlank()) throw new IllegalArgumentException("toolName must not be blank");
        if (arguments == null) arguments = JsonValue.of(Map.of());
        String body = McpJsonRpc.request(2, "tools/call", JsonValue.of(Map.of(
                "name", JsonValue.of(toolName),
                "arguments", arguments)));
        HttpTransport.ResponseStream response = transport.postJson(
                URI.create(config.url()), headers(), body,
                Duration.ofMillis(config.executionTimeoutMs()));
        if (response.status() == 401) {
            response.close();
            throw new McpNeedsAuth("oauth required for " + config.name());
        }
        String text = readBody(response);
        response.close();
        Result<JsonValue> parsed = McpJsonRpc.parse(text);
        if (parsed instanceof Result.Err<JsonValue> error) throw new McpFailure(error.error().message());
        Option<String> error = McpJsonRpc.error(parsed.valueOrNull());
        if (error.isPresent()) throw new McpFailure(error.orElseThrow());
        JsonValue result = McpJsonRpc.result(parsed.valueOrNull());
        return result == null ? JsonValue.of(Map.of()) : result;
    }

    @Override
    public List<McpRegistrar.McpToolSpec> listTools() {
        List<McpRegistrar.McpToolSpec> cached = discovered;
        if (cached != null) return cached;
        String initBody = McpJsonRpc.initialize();
        HttpTransport.ResponseStream initResponse = transport.postJson(
                URI.create(config.url()), headers(), initBody,
                Duration.ofMillis(config.startupTimeoutMs()));
        if (initResponse.status() == 401) {
            initResponse.close();
            throw new McpNeedsAuth("oauth required for " + config.name());
        }
        String initText = readBody(initResponse);
        sessionId = header(initResponse, "mcp-session-id");
        initResponse.close();
        Result<JsonValue> initParsed = McpJsonRpc.parse(initText);
        if (initParsed instanceof Result.Err<JsonValue> error) throw new McpFailure(error.error().message());
        Option<String> initError = McpJsonRpc.error(initParsed.valueOrNull());
        if (initError.isPresent()) throw new McpFailure(initError.orElseThrow());

        String body = McpJsonRpc.toolsList();
        HttpTransport.ResponseStream response = transport.postJson(
                URI.create(config.url()), headers(), body,
                Duration.ofMillis(config.catalogTimeoutMs()));
        if (response.status() == 401) {
            response.close();
            throw new McpNeedsAuth("oauth required for " + config.name());
        }
        String text = readBody(response);
        response.close();
        Result<JsonValue> parsed = McpJsonRpc.parse(text);
        if (parsed instanceof Result.Err<JsonValue> error) throw new McpFailure(error.error().message());
        Option<String> err = McpJsonRpc.error(parsed.valueOrNull());
        if (err.isPresent()) throw new McpFailure(err.orElseThrow());
        discovered = McpJsonRpc.tools(McpJsonRpc.result(parsed.valueOrNull()));
        return discovered;
    }

    public List<McpRegistrar.McpToolSpec> reload() {
        discovered = null;
        return listTools();
    }

    /** Exchanges an authorization code and stores the resulting token. */
    public void authenticate(String code, String redirectUri, String codeVerifier,
                             String clientId, String clientSecret) {
        if (code == null || code.isBlank() || redirectUri == null) {
            throw new IllegalArgumentException("code and redirectUri must not be blank");
        }
        if (credentials.isEmpty()) throw new McpFailure("no credential store configured");
        McpOAuth.OAuthMetadata metadata = McpOAuth.discover(transport, config.url());
        McpOAuth.OAuthToken token = McpOAuth.exchangeCode(transport, metadata.tokenEndpoint(),
                clientId, clientSecret, code, redirectUri, codeVerifier);
        CredentialStore store = credentials.orElseThrow();
        Map<String, Object> meta = new java.util.LinkedHashMap<>();
        token.refreshToken().ifPresent(value -> meta.put("refreshToken", value));
        token.expiresAt().ifPresent(value -> meta.put("expires", value.toString()));
        store.create(new CredentialStore.CredentialInput(integrationId, "oauth",
                token.accessToken(), meta));
        Credential latest = store.saved(integrationId).stream()
                .max(java.util.Comparator.comparing(Credential::created)).orElseThrow();
        store.activate(latest.id());
        discovered = null;
    }

    /** Refreshes the stored token if it is about to expire. */
    public String authorizationUrl(String clientId, String redirectUri, String state) {
        McpOAuth.OAuthMetadata metadata = McpOAuth.discover(transport, config.url());
        List<String> scopes = metadata.scopes().isEmpty() ? List.of("mcp") : metadata.scopes();
        return McpOAuth.authorizationUrl(metadata, clientId, redirectUri, state, null, scopes);
    }

    public boolean refreshIfNeeded(String clientId, String clientSecret) {
        if (credentials.isEmpty()) return false;
        CredentialStore store = credentials.orElseThrow();
        for (Credential credential : store.saved(integrationId)) {
            if (!credential.active() || !"oauth".equals(credential.kind())) continue;
            Object expires = credential.metadata().get("expires");
            if (expires == null) return false;
            try {
                java.time.Instant expiry = java.time.Instant.parse(String.valueOf(expires));
                if (expiry.isAfter(java.time.Instant.now().plusSeconds(300))) return false;
            } catch (Exception e) {
                return false;
            }
            String refreshToken = (String) credential.metadata().get("refreshToken");
            if (refreshToken == null || refreshToken.isBlank()) return false;
            McpOAuth.OAuthMetadata metadata = McpOAuth.discover(transport, config.url());
            McpOAuth.OAuthToken token = McpOAuth.refreshToken(transport, metadata.tokenEndpoint(),
                    clientId, clientSecret, refreshToken);
            store.create(new CredentialStore.CredentialInput(integrationId, "oauth",
                    token.accessToken(), Map.of("refreshToken",
                            token.refreshToken().orElse(refreshToken),
                            "expires", token.expiresAt().map(Object::toString).orElse(""))));
            Credential latest = store.saved(integrationId).stream()
                    .max(java.util.Comparator.comparing(Credential::created)).orElseThrow();
            store.activate(latest.id());
            discovered = null;
            return true;
        }
        return false;
    }

    private Map<String, String> headers() {
        Map<String, String> headers = new java.util.LinkedHashMap<>(config.headers());
        headers.putIfAbsent("Accept", "application/json, text/event-stream");
        if (sessionId != null && !sessionId.isBlank()) {
            headers.putIfAbsent("Mcp-Session-Id", sessionId);
        }
        if (credentials.isPresent()) {
            for (Credential credential : credentials.orElseThrow().saved(integrationId)) {
                if (credential.active() && credential.secret().isPresent()) {
                    headers.put("Authorization", "Bearer " + credential.secret().orElseThrow());
                    break;
                }
            }
        }
        return Map.copyOf(headers);
    }

    private static String header(HttpTransport.ResponseStream response, String name) {
        if (response == null || name == null) return null;
        for (Map.Entry<String, List<String>> entry : response.headers().entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name)
                    && entry.getValue() != null && !entry.getValue().isEmpty()) {
                return entry.getValue().get(0);
            }
        }
        return null;
    }

    private static String readBody(HttpTransport.ResponseStream response) {
        try {
            return new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
