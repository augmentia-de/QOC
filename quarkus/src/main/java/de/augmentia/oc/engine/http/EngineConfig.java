package de.augmentia.oc.engine.http;

import de.augmentia.oc.engine.core.error.Defect;
import de.augmentia.oc.engine.core.json.Json;
import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.json.Result;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * L-2 configuration: strict JSON, global file then project file; project wins
 * field by field, lists are appended. Unknown keys warn.
 */
@ApplicationScoped
public class EngineConfig {

    private static final Logger LOG = Logger.getLogger(EngineConfig.class.getName());
    private static final List<String> KNOWN = List.of(
            "model", "provider", "workspace", "auth", "agents");

    @Inject
    @ConfigProperty(name = "engine.workspace", defaultValue = ".")
    String workspaceProperty;

    @Inject
    @ConfigProperty(name = "engine.model.provider", defaultValue = "openai")
    String providerProperty;

    @Inject
    @ConfigProperty(name = "engine.model.id", defaultValue = "gpt-4o-mini")
    String modelProperty;

    @Inject
    @ConfigProperty(name = "engine.provider.url",
            defaultValue = "https://api.openai.com/v1/chat/completions")
    String urlProperty;

    @Inject
    @ConfigProperty(name = "engine.auth.password")
    java.util.Optional<String> passwordProperty;

    private String provider;
    private String model;
    private String workspace;
    private String url;
    private String password;
    private List<AgentConfig> agents;
    private final List<ConfigEntry> entries = new ArrayList<>();

    @PostConstruct
    void load() {
        workspace = workspaceProperty;
        provider = providerProperty;
        model = modelProperty;
        url = urlProperty;
        password = passwordProperty.orElse("");
        agents = new ArrayList<>();

        Map<String, JsonValue> merged = new LinkedHashMap<>();
        merge(merged, read(globalFile()));
        if (merged.get("workspace") instanceof JsonValue.Str globalWorkspace) {
            workspace = globalWorkspace.value();
        }
        merge(merged, read(projectFile()));
        merged.keySet().stream().filter(key -> !KNOWN.contains(key))
                .forEach(key -> LOG.warning("unknown opencode.json key: " + key));

        if (merged.get("workspace") instanceof JsonValue.Str value) workspace = value.value();
        if (merged.get("model") instanceof JsonValue.Obj modelObject) {
            provider = string(modelObject, "provider", provider);
            model = string(modelObject, "id", model);
        }
        if (merged.get("provider") instanceof JsonValue.Obj providerObject) {
            url = string(providerObject, "url", url);
        }
        if (merged.get("auth") instanceof JsonValue.Obj authObject) {
            password = string(authObject, "password", password);
        }
        if (merged.get("agents") instanceof JsonValue.Arr list) {
            for (JsonValue item : list.values()) {
                if (item instanceof JsonValue.Obj agent) {
                    agents.add(new AgentConfig(string(agent, "name", "build"),
                            string(agent, "system", null),
                            Boolean.TRUE.equals(bool(agent, "hidden"))));
                }
            }
        }
        if (agents.isEmpty()) {
            agents.add(new AgentConfig("build", null, false));
        }

        entries.add(new ConfigEntry("model.provider", provider, "config"));
        entries.add(new ConfigEntry("model.id", model, "config"));
        entries.add(new ConfigEntry("provider.url", url, "config"));
        entries.add(new ConfigEntry("workspace", workspace, "config"));
        entries.add(new ConfigEntry("auth.password", password == null || password.isBlank() ? "" : "***", "config"));
    }

    public String provider() { return provider; }
    public String model() { return model; }
    public String workspace() { return workspace; }
    public String url() { return url; }
    public String password() { return password == null ? "" : password; }
    public List<AgentConfig> agents() { return List.copyOf(agents); }
    public List<ConfigEntry> entries() { return List.copyOf(entries); }

    private JsonValue read(Path file) {
        if (!Files.exists(file)) return JsonValue.of(Map.of());
        try {
            Result<JsonValue> parsed = Json.parse(Files.readString(file));
            if (parsed instanceof Result.Err<JsonValue> error) {
                throw new Defect("invalid configuration " + file + ": " + error.error().message());
            }
            return parsed.valueOrNull();
        } catch (java.io.IOException e) {
            throw new Defect("cannot read configuration " + file, e);
        }
    }

    private Path globalFile() {
        String home = System.getProperty("user.home", ".");
        return Path.of(home, ".config", "opencode", "opencode.json");
    }

    private Path projectFile() {
        return Path.of(workspace).resolve("opencode.json");
    }

    private static void merge(Map<String, JsonValue> target, JsonValue source) {
        if (!(source instanceof JsonValue.Obj object)) return;
        for (Map.Entry<String, JsonValue> entry : object.values().entrySet()) {
            JsonValue existing = target.get(entry.getKey());
            if (existing instanceof JsonValue.Obj left && entry.getValue() instanceof JsonValue.Obj right) {
                Map<String, JsonValue> nested = new LinkedHashMap<>(left.values());
                merge(nested, right);
                target.put(entry.getKey(), JsonValue.of(nested));
            } else if (existing instanceof JsonValue.Arr left && entry.getValue() instanceof JsonValue.Arr right) {
                List<JsonValue> appended = new ArrayList<>(left.values());
                appended.addAll(right.values());
                target.put(entry.getKey(), JsonValue.of(appended));
            } else {
                target.put(entry.getKey(), entry.getValue());
            }
        }
    }

    private static String string(JsonValue.Obj object, String key, String fallback) {
        return object.values().get(key) instanceof JsonValue.Str str ? str.value() : fallback;
    }

    private static Boolean bool(JsonValue.Obj object, String key) {
        return object.values().get(key) instanceof JsonValue.Bool value ? value.value() : null;
    }

    public record AgentConfig(String name, String system, boolean hidden) {}

    public record ConfigEntry(String key, String value, String source) {}
}
