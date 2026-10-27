package de.augmentia.oc.engine.http.security;

import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.http.EngineConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

@ApplicationScoped
public class SecurityConfig {
    @Inject
    EngineConfig config;

    public AuthMode mode() {
        if (config == null) {
            return AuthMode.NONE;
        }
        String authMode = System.getProperty("engine.security.mode", System.getenv("ENGINE_SECURITY_MODE"));
        if (authMode == null || authMode.isBlank()) {
            authMode = readFromSettings("security.mode");
        }
        AuthMode resolved = AuthMode.from(authMode);
        if (resolved == AuthMode.NONE && config.password() != null && !config.password().isBlank()) {
            return AuthMode.BASIC;
        }
        return resolved;
    }

    public List<String> trustedProxies() {
        try {
            java.nio.file.Path global = java.nio.file.Path.of(System.getProperty("user.home", "."), ".config", "opencode", "settings.json");
            java.nio.file.Path project = java.nio.file.Path.of(config.workspace() == null ? "." : config.workspace()).resolve("settings.json");
            List<String> fromGlobal = readStringList(global, "security.trustedProxies");
            List<String> fromProject = readStringList(project, "security.trustedProxies");
            if (fromProject != null && !fromProject.isEmpty()) {
                return fromProject;
            }
            return fromGlobal == null ? List.of() : fromGlobal;
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<String> readStringList(java.nio.file.Path path, String key) {
        if (!java.nio.file.Files.exists(path)) {
            return null;
        }
        try {
            String content = java.nio.file.Files.readString(path);
            de.augmentia.oc.engine.core.json.Result<de.augmentia.oc.engine.core.json.JsonValue> parsed = de.augmentia.oc.engine.core.json.Json.parse(content);
            if (!(parsed instanceof de.augmentia.oc.engine.core.json.Result.Ok<de.augmentia.oc.engine.core.json.JsonValue> ok)) {
                return null;
            }
            JsonValue current = ok.value();
            String[] parts = key.split("\\.");
            for (int i = 0; i < parts.length - 1; i++) {
                if (!(current instanceof JsonValue.Obj obj)) {
                    return null;
                }
                current = obj.values().get(parts[i]);
            }
            if (!(current instanceof JsonValue.Obj obj)) {
                return null;
            }
            JsonValue list = obj.values().get(parts[parts.length - 1]);
            if (!(list instanceof de.augmentia.oc.engine.core.json.JsonValue.Arr arr)) {
                return null;
            }
            java.util.List<String> result = new java.util.ArrayList<>();
            for (JsonValue item : arr.values()) {
                if (item instanceof de.augmentia.oc.engine.core.json.JsonValue.Str str && !str.value().isBlank()) {
                    result.add(str.value());
                }
            }
            return result.isEmpty() ? null : result;
        } catch (Exception e) {
            return null;
        }
    }

    public boolean authenticationRequired() {
        AuthMode m = mode();
        return m == AuthMode.BASIC || m == AuthMode.OIDC || m == AuthMode.PROXY || m == AuthMode.MCP_OAUTH;
    }

    private String readFromSettings(String key) {
        try {
            java.nio.file.Path global = java.nio.file.Path.of(System.getProperty("user.home", "."), ".config", "opencode", "settings.json");
            java.nio.file.Path project = java.nio.file.Path.of(config.workspace() == null ? "." : config.workspace()).resolve("settings.json");
            String value = readValue(global, key);
            if (value != null) {
                return value;
            }
            return readValue(project, key);
        } catch (Exception e) {
            return null;
        }
    }

    private String readValue(java.nio.file.Path path, String key) {
        if (!java.nio.file.Files.exists(path)) {
            return null;
        }
        try {
            String content = java.nio.file.Files.readString(path);
            de.augmentia.oc.engine.core.json.Result<de.augmentia.oc.engine.core.json.JsonValue> parsed = de.augmentia.oc.engine.core.json.Json.parse(content);
            if (!(parsed instanceof de.augmentia.oc.engine.core.json.Result.Ok<de.augmentia.oc.engine.core.json.JsonValue> ok)) {
                return null;
            }
            JsonValue current = ok.value();
            String[] parts = key.split("\\.");
            for (int i = 0; i < parts.length - 1; i++) {
                if (!(current instanceof JsonValue.Obj obj)) {
                    return null;
                }
                current = obj.values().get(parts[i]);
            }
            if (current instanceof JsonValue.Obj obj && obj.values().get(parts[parts.length - 1]) instanceof JsonValue.Str str) {
                return str.value();
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }
}
