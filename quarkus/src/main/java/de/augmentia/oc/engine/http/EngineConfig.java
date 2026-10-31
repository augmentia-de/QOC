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
import de.augmentia.oc.engine.runtime.skill.SkillFilterOptions;
import java.util.logging.Logger;

/**
 * L-2 configuration: strict JSON, global file then project file; project wins
 * field by field, lists are appended. Unknown keys warn.
 */
@ApplicationScoped
public class EngineConfig {

    private static final Logger LOG = Logger.getLogger(EngineConfig.class.getName());
    private static final List<String> KNOWN = List.of(
            "model", "provider", "workspace", "auth", "agents", "mcp", "skills", "guardrails");
    @Inject
    @ConfigProperty(name = "engine.workspace", defaultValue = ".")
    String workspaceProperty;

    @Inject
    @ConfigProperty(name = "engine.settings.global")
    java.util.Optional<String> globalSettingsFileProperty;

    @Inject
    @ConfigProperty(name = "engine.session.temp-dir", defaultValue = "sessions")
    String sessionTempDirProperty;

    @Inject
    @ConfigProperty(name = "engine.settings.workspace-enabled", defaultValue = "true")
    boolean workspaceSettingsEnabledProperty;

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
    private String sessionTempDir;
    private boolean workspaceSettingsEnabled = true;
    private String url;
    private String password;
    private List<AgentConfig> agents;
    private List<de.augmentia.oc.engine.runtime.tool.mcp.McpServerConfig> mcpServers;
    private boolean skillsEnabled;
    private List<String> skillPaths;
    private List<String> skillAllow;
    private List<String> skillDeny;
    private int skillMax;
    private SkillFilterOptions skillFilter;
    private Map<String, AgentSkillsConfig> agentSkills;
    private WebhookConfig webhookConfig;
    private final List<ConfigEntry> entries = new ArrayList<>();
    @PostConstruct
    void load() {
        agents = new ArrayList<>();
        mcpServers = new ArrayList<>();
        skillPaths = new ArrayList<>();
        skillAllow = new ArrayList<>();
        skillDeny = new ArrayList<>();
        skillMax = 0;
        skillFilter = SkillFilterOptions.defaults();
        agentSkills = new LinkedHashMap<>();
        webhookConfig = WebhookConfig.disabled();
        entries.clear();        workspace = workspaceProperty;
        sessionTempDir = sessionTempDirProperty;
        workspaceSettingsEnabled = workspaceSettingsEnabledProperty;
        provider = providerProperty;
        model = modelProperty;
        url = urlProperty;
        password = passwordProperty.orElse("");
        agents = new ArrayList<>();
        mcpServers = new ArrayList<>();
        skillsEnabled = true;
        skillPaths = new ArrayList<>();
        skillAllow = new ArrayList<>();
        skillDeny = new ArrayList<>();
        skillMax = 0;
        skillFilter = SkillFilterOptions.defaults();
        agentSkills = new LinkedHashMap<>();

        Map<String, JsonValue> merged = new LinkedHashMap<>();
        // Security: agents and permissions come ONLY from the global (user-level)
        // settings.json, never from the project/workspace settings.json. This
        // prevents a workspace owner from defining arbitrary agents or rights.
        merge(merged, read(globalFile()));
        if (merged.get("workspace") instanceof JsonValue.Str globalWorkspace) {
            workspace = globalWorkspace.value();
        }
        // Project/workspace settings are optional and can be disabled entirely
        // via engine.settings.workspace-enabled=false, so that only the
        // user-level (global) settings.json defines agents and permissions.
        if (workspaceSettingsEnabled) {
            // Project/workspace settings override non-security keys (workspace,
            // skills, mcp, guardrails, model, provider, ...) field by field.
            // Agents and permissions are still allowed from the project file for
            // backward compatibility; set engine.settings.workspace-enabled=false
            // to disable project-level settings entirely (agents/permissions then
            // come only from the global settings.json).
            merge(merged, read(projectFile()));
        } else {
            LOG.info("workspace settings disabled: settings/agents/permissions come only from the global settings.json");
        }
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
                    int maxSteps = stepLimit(agent);
                    String model = string(agent, "model", null);
                    String mode = string(agent, "mode", "all");
                    List<Rule> permissions = rules(agent);
                    String name = string(agent, "name", "build");
                    agents.add(new AgentConfig(name,
                            string(agent, "system", null),
                            Boolean.TRUE.equals(bool(agent, "hidden")),
                            mode, model, maxSteps, toolAllow, toolDeny, permissions, mcpServers));
                    AgentSkillsConfig skills = parseAgentSkills(agent);
                    if (skills != null) {
                        agentSkills.put(name.toLowerCase(java.util.Locale.ROOT), skills);
                    }
                }
            }
        }
        if (agents.isEmpty()) {
            agents.add(defaultBuildAgent());
            agents.add(defaultPlanAgent());
        } else {
            // The engine baseline needs "build" and "plan" (plan mode, subagent
            // exclusions, AISDLC profile). A project config that declares only
            // its own roles must not make them disappear.
            ensureBaselineAgents(agents);
        }
        if (merged.get("skills") instanceof JsonValue.Obj skillsObject) {
            if (Boolean.FALSE.equals(bool(skillsObject, "enabled"))) skillsEnabled = false;
            skillAllow = patterns(skillsObject, "allow");
            skillDeny = patterns(skillsObject, "deny");
            skillMax = (int) Math.max(0, number(skillsObject, "max", 0));
            if (skillsObject.values().get("filter") instanceof JsonValue.Obj filter) {
                boolean enabled = !Boolean.FALSE.equals(bool(filter, "enabled"));
                String strategy = string(filter, "strategy", "bm25");
                int topK = (int) Math.max(1, number(filter, "topK", 12));
                double minScore = decimal(filter, "minScore", 0.25);
                int thresholdCount = (int) Math.max(0, number(filter, "thresholdCount", 20));
                List<String> alwaysInclude = stringList(filter, "alwaysInclude");
                skillFilter = new SkillFilterOptions(enabled, strategy, topK, minScore, thresholdCount, alwaysInclude);
            }
            if (skillsObject.values().get("paths") instanceof JsonValue.Arr paths) {
                for (JsonValue item : paths.values()) {
                    if (item instanceof JsonValue.Str path && !path.value().isBlank()) {
                        skillPaths.add(path.value());
                    }
                }
            }
        }
        if (merged.get("mcp") instanceof JsonValue.Obj mcp) {
            for (Map.Entry<String, JsonValue> entry : mcp.values().entrySet()) {
                if (!(entry.getValue() instanceof JsonValue.Obj server)) continue;
                mcpServers.add(parseMcp(entry.getKey(), server));
            }
        }
        webhookConfig = parseWebhook(merged.get("guardrails"));

        entries.add(new ConfigEntry("model.provider", provider, "config"));
        entries.add(new ConfigEntry("model.id", model, "config"));
        entries.add(new ConfigEntry("provider.url", url, "config"));
        entries.add(new ConfigEntry("workspace", workspace, "config"));
        entries.add(new ConfigEntry("auth.password", password == null || password.isBlank() ? "" : "***", "config"));
        entries.add(new ConfigEntry("guardrails.webhook.enabled", String.valueOf(webhookConfig.enabled()), "config"));
        if (webhookConfig.enabled()) {
            entries.add(new ConfigEntry("guardrails.webhook.url", webhookConfig.url(), "config"));
            entries.add(new ConfigEntry("guardrails.webhook.mode", webhookConfig.mode(), "config"));
        }
    }

    public void reload() {
        load();
    }

    public String provider() { return provider; }
    public String model() { return model; }
    public String workspace() { return workspace; }
    public String sessionTempDir() { return sessionTempDir == null || sessionTempDir.isBlank() ? "sessions" : sessionTempDir; }
    public boolean workspaceSettingsEnabled() { return workspaceSettingsEnabled; }
    public String url() { return url; }
    public String password() { return password == null ? "" : password; }
    public List<AgentConfig> agents() { return List.copyOf(agents); }
    public List<de.augmentia.oc.engine.runtime.tool.mcp.McpServerConfig> mcpServers() {
        return List.copyOf(mcpServers);
    }
    /** Skill discovery is on by default; {@code skills.enabled=false} disables it. */
    public boolean skillsEnabled() { return skillsEnabled; }
    /** Additional skill roots from {@code skills.paths}, in order (later wins). */
    public List<String> skillPaths() { return List.copyOf(skillPaths); }
    /** If non-empty, only matching skill IDs are loaded. */
    public List<String> skillAllow() { return List.copyOf(skillAllow); }
    /** Matching skill IDs are never loaded (deny wins over allow). */
    public List<String> skillDeny() { return List.copyOf(skillDeny); }
    /** Caps the number of loaded skills (0 = unlimited). */
    public int skillMax() { return skillMax; }
    /** Skill filtering options used when building prompts. */
    public SkillFilterOptions skillFilter() { return skillFilter == null ? SkillFilterOptions.defaults() : skillFilter; }
    /** Per-agent skill configuration, or {@code null} when the agent has none. */
    public AgentSkillsConfig agentSkills(String name) {
        if (name == null || agentSkills == null) return null;
        return agentSkills.get(name.toLowerCase(java.util.Locale.ROOT));
    }
    public WebhookConfig webhook() { return webhookConfig == null ? WebhookConfig.disabled() : webhookConfig; }
    public boolean webhookEnabled() { return webhook() != null && webhook().enabled(); }
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

    private static WebhookConfig parseWebhook(JsonValue value) {
        if (!(value instanceof JsonValue.Obj guardrails)) return WebhookConfig.disabled();
        JsonValue webhookValue = guardrails.values().get("webhook");
        JsonValue.Obj obj = null;
        if (webhookValue instanceof JsonValue.Obj webhookObj) {
            obj = webhookObj;
        } else if (guardrails.values().get("webhooks") instanceof JsonValue.Arr arr && !arr.values().isEmpty()) {
            if (arr.values().get(0) instanceof JsonValue.Obj first) {
                obj = first;
            }
        }
        if (obj == null) return WebhookConfig.disabled();
        boolean enabled = Boolean.TRUE.equals(bool(obj, "enabled"));
        if (!enabled) return WebhookConfig.disabled();
        String url = string(obj, "url", "");
        if (url == null || url.isBlank()) return WebhookConfig.disabled();
        String mode = string(obj, "mode", "enabled");
        int timeoutMs = (int) number(obj, "timeoutMs", 1500);
        String failMode = string(obj, "failMode", "closed-before-open-after");
        int maxPayloadBytes = (int) number(obj, "maxPayloadBytes", 65536);
        String payloadMode = string(obj, "payloadMode", "full");
        boolean tlsRequired = !Boolean.FALSE.equals(bool(obj, "tlsRequired"));
        String authType = string(obj, "authType", "hmac-sha256");
        String secretRef = string(obj, "secretRef", null);
        List<String> allowedHosts = stringList(obj, "allowedHosts");
        List<String> allowedIps = stringList(obj, "allowedIps");
        int clockSkewSeconds = (int) number(obj, "clockSkewSeconds", 60);
        if (obj.values().get("security") instanceof JsonValue.Obj security) {
            tlsRequired = !Boolean.FALSE.equals(bool(security, "tlsRequired"));
            authType = string(security, "authType", authType);
            secretRef = string(security, "secretRef", secretRef);
            if (security.values().get("allowedHosts") instanceof JsonValue.Arr) {
                allowedHosts = stringList(security, "allowedHosts");
            }
            if (security.values().get("allowedIps") instanceof JsonValue.Arr) {
                allowedIps = stringList(security, "allowedIps");
            }
            clockSkewSeconds = (int) number(security, "clockSkewSeconds", clockSkewSeconds);
        }
        boolean piiEnabled = false;
        String piiDefaultMode = "detect";
        if (obj.values().get("pii") instanceof JsonValue.Obj pii) {
            piiEnabled = Boolean.TRUE.equals(bool(pii, "enabled"));
            piiDefaultMode = string(pii, "defaultMode", piiDefaultMode);
        }
        return new WebhookConfig(true, mode, url, timeoutMs, failMode, maxPayloadBytes, payloadMode,
                tlsRequired, authType, secretRef, allowedHosts, allowedIps, clockSkewSeconds, piiEnabled, piiDefaultMode);
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

    /**
     * Parses {@code agents[].skills}. Returns {@code null} when the block is
     * absent, so the global behaviour stays unchanged.
     */
    private static AgentSkillsConfig parseAgentSkills(JsonValue.Obj agent) {
        if (!(agent.values().get("skills") instanceof JsonValue.Obj obj)) return null;
        boolean enabled = !Boolean.FALSE.equals(bool(obj, "enabled"));
        List<String> allow = patterns(obj, "allow");
        List<String> deny = patterns(obj, "deny");
        int max = (int) Math.max(0, number(obj, "max", 0));
        String prompt = string(obj, "prompt", "top-k");
        List<String> core = patterns(obj, "core");
        SkillFilterOptions filter = null;
        if (obj.values().get("filter") instanceof JsonValue.Obj filterObject) {
            boolean filterEnabled = !Boolean.FALSE.equals(bool(filterObject, "enabled"));
            String strategy = string(filterObject, "strategy", "bm25");
            int topK = (int) Math.max(1, number(filterObject, "topK", 12));
            double minScore = decimal(filterObject, "minScore", 0.25);
            int thresholdCount = (int) Math.max(0, number(filterObject, "thresholdCount", 20));
            List<String> alwaysInclude = patterns(filterObject, "alwaysInclude");
            filter = new SkillFilterOptions(filterEnabled, strategy, topK, minScore,
                    thresholdCount, alwaysInclude);
        }
        return new AgentSkillsConfig(enabled, allow, deny, max, prompt, core, filter);
    }

    private static long number(JsonValue.Obj object, String key, long fallback) {
        return object.values().get(key) instanceof JsonValue.Num num ? num.value().longValue() : fallback;
    }

    private static double decimal(JsonValue.Obj object, String key, double fallback) {
        return object.values().get(key) instanceof JsonValue.Num num ? num.value().doubleValue() : fallback;
    }

    /**
     * OpenCode calls the optional limit {@code steps}; {@code maxSteps} is the
     * deprecated alias. An absent limit means no step limit.
     */
    private static int stepLimit(JsonValue.Obj agent) {
        long steps = number(agent, "steps", -1);
        long legacy = number(agent, "maxSteps", -1);
        long configured = steps > 0 ? steps : legacy > 0 ? legacy : 0;
        return configured > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) configured;
    }

    private Path globalFile() {
        if (globalSettingsFileProperty != null && globalSettingsFileProperty.isPresent()
                && !globalSettingsFileProperty.get().isBlank()) {
            return Path.of(globalSettingsFileProperty.get());
        }
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

    private static List<String> patterns(JsonValue.Obj object, String key) {
        if (!(object.values().get(key) instanceof JsonValue.Arr values)) return new ArrayList<>();
        return values.values().stream()
                .filter(JsonValue.Str.class::isInstance)
                .map(value -> ((JsonValue.Str) value).value())
                .filter(value -> !value.isBlank())
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }

    /** Baseline build agent with the secure defaults. */
    private static AgentConfig defaultBuildAgent() {
        String planDir = planDirectory();
        return new AgentConfig("build", null, false, "all", null, 0,
                List.of("*"), List.of(),
                List.of(
                        new Rule("*", "*", Effect.ALLOW),
                        new Rule("external_directory", "*", Effect.DENY),
                        new Rule("read", "*.env", Effect.ASK),
                        new Rule("edit", "*.env", Effect.DENY),
                        new Rule("shell", "*", Effect.ASK),
                        new Rule("external_directory", planDir + "/*", Effect.ALLOW)),
                List.of("*"));
    }

    /** Baseline plan agent: no edits except the plan directory. */
    private static AgentConfig defaultPlanAgent() {
        String planDir = planDirectory();
        return new AgentConfig("plan", "You are in plan mode. Do not edit files.",
                false, "all", null, 0, List.of("*"), List.of(),
                List.of(
                        new Rule("*", "*", Effect.ALLOW),
                        new Rule("edit", "*", Effect.DENY),
                        new Rule("external_directory", "*", Effect.DENY),
                        new Rule("read", "*.env", Effect.ASK),
                        new Rule("shell", "*", Effect.ASK),
                        new Rule("external_directory", planDir + "/*", Effect.ALLOW)),
                List.of("*"));
    }

    /**
     * Keeps the engine baseline available even when a project config declares
     * only its own roles. Configured agents win; missing baseline agents are
     * appended.
     */
    private static void ensureBaselineAgents(List<AgentConfig> agents) {
        java.util.Set<String> names = agents.stream()
                .map(agent -> agent.name().toLowerCase(java.util.Locale.ROOT))
                .collect(java.util.stream.Collectors.toSet());
        if (!names.contains("build")) agents.add(defaultBuildAgent());
        if (!names.contains("plan")) agents.add(defaultPlanAgent());
    }

    public record AgentConfig(String name, String system, boolean hidden, String mode,
                              String model, int maxSteps,
                              List<String> toolAllow, List<String> toolDeny,
                              List<Rule> permissions, List<String> mcpServers) {
        public AgentConfig {
            if (name == null || name.isBlank()) name = "build";
            if (mode == null || mode.isBlank()) mode = "all";
            if (maxSteps < 0) maxSteps = 0;
            toolAllow = toolAllow == null ? List.of("*") : List.copyOf(toolAllow);
            toolDeny = toolDeny == null ? List.of() : List.copyOf(toolDeny);
            permissions = permissions == null ? List.of() : List.copyOf(permissions);
            mcpServers = mcpServers == null ? List.of("*") : List.copyOf(mcpServers);
        }
        // backward-compatible constructor with secure defaults
        public AgentConfig(String name, String system, boolean hidden) {
            this(name, system, hidden, "all", null, 0, List.of("*"), List.of(),
                    List.of(
                            new Rule("*", "*", Effect.ALLOW),
                            new Rule("external_directory", "*", Effect.DENY),
                            new Rule("read", "*.env", Effect.ASK),
                            new Rule("edit", "*.env", Effect.DENY),
                            new Rule("shell", "*", Effect.ASK),
                            new Rule("external_directory", EngineConfig.planDirectory() + "/*", Effect.ALLOW)),
                    List.of("*"));
        }

        // backward-compatible constructor for callers that do not pass mode
        public AgentConfig(String name, String system, boolean hidden, String model, int maxSteps,
                           List<String> toolAllow, List<String> toolDeny,
                           List<Rule> permissions, List<String> mcpServers) {
            this(name, system, hidden, "all", model, maxSteps, toolAllow, toolDeny, permissions, mcpServers);
        }
    }

    /**
     * Per-agent skill configuration. Absent for agents without a
     * {@code skills} block; {@link #enabled()} defaults to {@code true} when
     * the block exists.
     */
    public record AgentSkillsConfig(boolean enabled, List<String> allow, List<String> deny,
                                    int max, String prompt, List<String> core,
                                    SkillFilterOptions filter) {

        public AgentSkillsConfig {
            allow = allow == null ? List.of() : List.copyOf(allow);
            deny = deny == null ? List.of() : List.copyOf(deny);
            if (max < 0) max = 0;
            prompt = normalizePrompt(prompt);
            core = core == null ? List.of() : List.copyOf(core);
        }

        public static AgentSkillsConfig disabled() {
            return new AgentSkillsConfig(false, List.of(), List.of(), 0, "top-k", List.of(), null);
        }

        private static String normalizePrompt(String value) {
            if (value == null || value.isBlank()) return "top-k";
            return switch (value.trim().toLowerCase(java.util.Locale.ROOT)) {
                case "none", "off" -> "none";
                case "core" -> "core";
                case "all" -> "all";
                default -> "top-k";
            };
        }
    }

    public record ConfigEntry(String key, String value, String source) {}

    public record WebhookConfig(boolean enabled, String mode, String url, int timeoutMs, String failMode,
                                int maxPayloadBytes, String payloadMode, boolean tlsRequired, String authType,
                                String secretRef, List<String> allowedHosts, List<String> allowedIps,
                                int clockSkewSeconds, boolean piiEnabled, String piiDefaultMode) {
        public WebhookConfig {
            allowedHosts = allowedHosts == null ? List.of() : List.copyOf(allowedHosts);
            allowedIps = allowedIps == null ? List.of() : List.copyOf(allowedIps);
            if (mode == null || mode.isBlank()) mode = "enabled";
            if (failMode == null || failMode.isBlank()) failMode = "closed-before-open-after";
            if (payloadMode == null || payloadMode.isBlank()) payloadMode = "full";
            if (authType == null || authType.isBlank()) authType = "hmac-sha256";
        }

        public static WebhookConfig disabled() {
            return new WebhookConfig(false, "disabled", "", 1500, "closed-before-open-after", 65536, "full",
                    true, "hmac-sha256", null, List.of(), List.of(), 60, false, "detect");
        }
    }
}
