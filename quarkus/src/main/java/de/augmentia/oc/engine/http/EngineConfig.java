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
import de.augmentia.oc.engine.core.model.Effect;
import de.augmentia.oc.engine.core.model.Rule;
import java.util.logging.Logger;

/**
 * L-2 configuration: strict JSON, global file then project file; project wins
 * field by field, lists are appended. Unknown keys warn.
 */
@ApplicationScoped
public class EngineConfig {

    private static final Logger LOG = Logger.getLogger(EngineConfig.class.getName());
    private static final List<String> KNOWN = List.of(
            "model", "provider", "workspace", "auth", "agents", "mcp");

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
    private List<de.augmentia.oc.engine.runtime.tool.mcp.McpServerConfig> mcpServers;
    private final List<ConfigEntry> entries = new ArrayList<>();

    @PostConstruct
    void load() {
        workspace = workspaceProperty;
        provider = providerProperty;
        model = modelProperty;
        url = urlProperty;
        password = passwordProperty.orElse("");
        agents = new ArrayList<>();
        mcpServers = new ArrayList<>();

        Map<String, JsonValue> merged = new LinkedHashMap<>();
        merge(merged, read(globalFile()));
        if (merged.get("workspace") instanceof JsonValue.Str globalWorkspace) {
            workspace = globalWorkspace.value();
        }
        merge(merged, read(projectFile()));
        merged.keySet().stream().filter(key -> !KNOWN.contains(key))
                .forEach(key -> LOG.warning("unknown settings.json key: " + key));

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
                    List<String> toolAllow = stringList(agent, "tools", "allow");
                    List<String> toolDeny = stringList(agent, "tools", "deny");
                    List<String> mcpServers = stringList(agent, "mcpServers", null);
                    int maxSteps = (int) number(agent, "maxSteps", 10);
                    String model = string(agent, "model", null);
                    List<Rule> permissions = rules(agent);
                    agents.add(new AgentConfig(string(agent, "name", "build"),
                            string(agent, "system", null),
                            Boolean.TRUE.equals(bool(agent, "hidden")),
                            model, maxSteps, toolAllow, toolDeny, permissions, mcpServers));
                }
            }
        }
        if (agents.isEmpty()) {
            // Secure defaults: allow-all, but deny external_directory and protect .env.
            // The Plan directory (~/.opencode/plan) is the only external location
            // that stays writable/readable, mirroring the reference implementation.
            String planDir = planDirectory();
            agents.add(new AgentConfig("build", null, false, null, 32,
                    List.of("*"), List.of(),
                    List.of(
                            new Rule("*", "*", Effect.ALLOW),
                            new Rule("external_directory", "*", Effect.DENY),
                            new Rule("read", "*.env", Effect.ASK),
                            new Rule("edit", "*.env", Effect.DENY),
                            new Rule("shell", "*", Effect.ASK),
                            new Rule("external_directory", planDir + "/*", Effect.ALLOW)),
                    List.of("*")));
            agents.add(new AgentConfig("plan", "You are in plan mode. Do not edit files.",
                    false, null, 10, List.of("*"), List.of(),
                    List.of(
                            new Rule("*", "*", Effect.ALLOW),
                            new Rule("edit", "*", Effect.DENY),
                            new Rule("external_directory", "*", Effect.DENY),
                            new Rule("read", "*.env", Effect.ASK),
                            new Rule("shell", "*", Effect.ASK),
                            new Rule("external_directory", planDir + "/*", Effect.ALLOW)),
                    List.of("*")));
        }
        if (merged.get("mcp") instanceof JsonValue.Obj mcp) {
            for (Map.Entry<String, JsonValue> entry : mcp.values().entrySet()) {
                if (!(entry.getValue() instanceof JsonValue.Obj server)) continue;
                mcpServers.add(parseMcp(entry.getKey(), server));
            }
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
    public List<de.augmentia.oc.engine.runtime.tool.mcp.McpServerConfig> mcpServers() {
        return List.copyOf(mcpServers);
    }
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

    private static de.augmentia.oc.engine.runtime.tool.mcp.McpServerConfig parseMcp(
            String name, JsonValue.Obj server) {
        String type = string(server, "type", server.values().containsKey("url") ? "remote" : "local");
        boolean disabled = Boolean.TRUE.equals(bool(server, "disabled"));
        boolean codemode = !Boolean.FALSE.equals(bool(server, "codemode"));
        int startup = (int) number(server, "startupTimeoutMs", 10_000);
        int catalog = (int) number(server, "catalogTimeoutMs", 10_000);
        int execution = (int) number(server, "executionTimeoutMs", 30_000);
        if ("remote".equals(type)) {
            return new de.augmentia.oc.engine.runtime.tool.mcp.McpServerConfig.Remote(
                    name, string(server, "url", ""), stringMap(server, "headers"),
                    Boolean.TRUE.equals(bool(server, "oauth")), disabled, codemode,
                    startup, catalog, execution);
        }
        return new de.augmentia.oc.engine.runtime.tool.mcp.McpServerConfig.Local(
                name, stringList(server, "command"), string(server, "cwd", "."),
                stringMap(server, "environment"), disabled, codemode, startup, catalog, execution);
    }

    private static Map<String, String> stringMap(JsonValue.Obj object, String key) {
        if (!(object.values().get(key) instanceof JsonValue.Obj values)) return Map.of();
        Map<String, String> map = new LinkedHashMap<>();
        for (Map.Entry<String, JsonValue> entry : values.values().entrySet()) {
            if (entry.getValue() instanceof JsonValue.Str str) map.put(entry.getKey(), str.value());
        }
        return map;
    }

    private static List<String> stringList(JsonValue.Obj object, String key) {
        if (!(object.values().get(key) instanceof JsonValue.Arr values)) return List.of();
        return values.values().stream()
                .filter(JsonValue.Str.class::isInstance)
                .map(value -> ((JsonValue.Str) value).value())
                .toList();
    }


    private static List<String> stringList(JsonValue.Obj object, String key, String nested) {
        JsonValue value = object.values().get(key);
        if (nested != null && value instanceof JsonValue.Obj obj) value = obj.values().get(nested);
        if (!(value instanceof JsonValue.Arr values)) return List.of();
        return values.values().stream()
                .filter(JsonValue.Str.class::isInstance)
                .map(v -> ((JsonValue.Str) v).value())
                .toList();
    }

    private static List<Rule> rules(JsonValue.Obj agent) {
        if (!(agent.values().get("permissions") instanceof JsonValue.Arr arr)) return List.of();
        List<Rule> out = new ArrayList<>();
        for (JsonValue item : arr.values()) {
            if (item instanceof JsonValue.Obj r) {
                String action = string(r, "action", "*");
                String resource = string(r, "resource", "*");
                String effectStr = string(r, "effect", "allow");
                Effect effect = switch (effectStr.toLowerCase()) {
                    case "deny" -> Effect.DENY;
                    case "ask" -> Effect.ASK;
                    default -> Effect.ALLOW;
                };
                out.add(new Rule(action, resource, effect));
            }
        }
        return out;
    }

    private static long number(JsonValue.Obj object, String key, long fallback) {
        return object.values().get(key) instanceof JsonValue.Num num ? num.value().longValue() : fallback;
    }

    private Path globalFile() {
        String home = System.getProperty("user.home", ".");
        return Path.of(home, ".config", "opencode", "settings.json");
    }

    private Path projectFile() {
        return Path.of(workspace).resolve("settings.json");
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

    /**
     * Plan mode writes to {@code ~/.opencode/plan}. Resolve it with the same
     * rule the filesystem tools use so the permission allow-list matches.
     */
    static String planDirectory() {
        return Path.of(System.getProperty("user.home", "."), ".opencode", "plan").toString();
    }

    public record AgentConfig(String name, String system, boolean hidden,
                              String model, int maxSteps,
                              List<String> toolAllow, List<String> toolDeny,
                              List<Rule> permissions, List<String> mcpServers) {
        public AgentConfig {
            if (name == null || name.isBlank()) name = "build";
            if (maxSteps <= 0) maxSteps = 10;
            toolAllow = toolAllow == null ? List.of("*") : List.copyOf(toolAllow);
            toolDeny = toolDeny == null ? List.of() : List.copyOf(toolDeny);
            permissions = permissions == null ? List.of() : List.copyOf(permissions);
            mcpServers = mcpServers == null ? List.of("*") : List.copyOf(mcpServers);
        }
        // backward-compatible constructor with secure defaults
        public AgentConfig(String name, String system, boolean hidden) {
            this(name, system, hidden, null, 32, List.of("*"), List.of(),
                    List.of(
                            new Rule("*", "*", Effect.ALLOW),
                            new Rule("external_directory", "*", Effect.DENY),
                            new Rule("read", "*.env", Effect.ASK),
                            new Rule("edit", "*.env", Effect.DENY),
                            new Rule("shell", "*", Effect.ASK),
                            new Rule("external_directory", EngineConfig.planDirectory() + "/*", Effect.ALLOW)),
                    List.of("*"));
        }
    }

    public record ConfigEntry(String key, String value, String source) {}
}
