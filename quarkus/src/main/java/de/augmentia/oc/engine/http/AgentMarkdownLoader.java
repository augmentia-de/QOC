package de.augmentia.oc.engine.http;

import de.augmentia.oc.engine.core.json.Json;
import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.json.Result;
import de.augmentia.oc.engine.core.model.Effect;
import de.augmentia.oc.engine.core.model.Rule;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Loads OpenCode-style agents from Markdown files with YAML/JSON frontmatter.
 * The Markdown body becomes the agent system prompt. Mirrors the discovery
 * used by OpenCode: global {@code ~/.config/opencode/agents} plus project
 * {@code .opencode/agents} directories.
 */
public final class AgentMarkdownLoader {

    private static final Logger LOG = Logger.getLogger(AgentMarkdownLoader.class.getName());

    private AgentMarkdownLoader() {}

    public static List<EngineConfig.AgentConfig> loadDirectory(Path agentsDir) {
        List<EngineConfig.AgentConfig> result = new ArrayList<>();
        if (agentsDir == null || !Files.isDirectory(agentsDir)) return result;
        try (var stream = Files.walk(agentsDir)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".md"))
                    .sorted()
                    .forEach(file -> {
                        EngineConfig.AgentConfig agent = parse(file, agentsDir);
                        if (agent != null) result.add(agent);
                    });
        } catch (IOException e) {
            LOG.warning("cannot scan agent directory " + agentsDir + ": " + e.getMessage());
        }
        return result;
    }

    public static List<Path> projectAgentDirs(Path workspace) {
        List<Path> dirs = new ArrayList<>();
        if (workspace == null) return dirs;
        Path current = workspace.toAbsolutePath().normalize();
        while (current != null) {
            Path agents = current.resolve(".opencode").resolve("agents");
            if (Files.isDirectory(agents)) dirs.add(0, agents);
            current = current.getParent();
        }
        return dirs;
    }

    public static Path globalAgentDir() {
        return Path.of(System.getProperty("user.home", "."), ".config", "opencode", "agents");
    }

    private static EngineConfig.AgentConfig parse(Path file, Path base) {
        String content;
        try {
            content = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOG.warning("cannot read agent file " + file + ": " + e.getMessage());
            return null;
        }
        String relative = base.relativize(file).toString().replace('\\', '/');
        if (relative.toLowerCase(Locale.ROOT).endsWith(".md")) {
            relative = relative.substring(0, relative.length() - 3);
        }
        return parseContent(content, relative);
    }

    /**
     * Parses an agent from Markdown content without touching the file system.
     * Used for inline agent definitions (MCP {@code qoc_run_agent_with_config})
     * so serverless/Lambda instances never need a writable agent directory.
     */
    public static EngineConfig.AgentConfig parseContent(String content, String fallbackName) {
        String relative = fallbackName == null || fallbackName.isBlank() ? "agent" : fallbackName;
        String normalized = content == null ? "" : content.startsWith("\uFEFF") ? content.substring(1) : content;
        if (!normalized.startsWith("---")) return null;
        String body = normalized;
        Map<String, String> frontmatter = new LinkedHashMap<>();
        List<Map<String, String>> permissionsBlock = new ArrayList<>();
        List<String> toolAllowBlock = null;
        List<String> toolDenyBlock = null;
        List<String> mcpServersBlock = null;
        List<String> rolesAllowedBlock = null;
        List<String> rolesBlock = null;
        List<String> rolesDashBlock = null;

        if (normalized.startsWith("---")) {
            int firstLineEnd = normalized.indexOf('\n');
            if (firstLineEnd < 0) return null;
            int closing = normalized.indexOf("\n---", firstLineEnd);
            if (closing < 0) return null;
            String fm = normalized.substring(firstLineEnd + 1, closing);
            body = normalized.substring(closing + 4);
            if (body.startsWith("\n")) body = body.substring(1);

            ParsedFrontmatter parsed = parseFrontmatter(fm);
            frontmatter = parsed.scalars();
            permissionsBlock = parsed.permissions();
            toolAllowBlock = parsed.list("toolAllow");
            if (toolAllowBlock == null) toolAllowBlock = parsed.list("allow");
            toolDenyBlock = parsed.list("toolDeny");
            if (toolDenyBlock == null) toolDenyBlock = parsed.list("deny");
            mcpServersBlock = parsed.list("mcpServers");
            rolesAllowedBlock = parsed.list("rolesAllowed");
            rolesBlock = parsed.list("roles");
            rolesDashBlock = parsed.list("roles-allowed");
        }

        String name = unquote(frontmatter.getOrDefault("name", relative));
        if (name.isBlank()) name = relative;
        if (Boolean.parseBoolean(frontmatter.getOrDefault("disabled", "false"))) return null;

        String mode = unquote(frontmatter.getOrDefault("mode", "all"));
        String model = unquote(frontmatter.get("model"));
        if (model != null && model.isBlank()) model = null;
        boolean hidden = Boolean.parseBoolean(frontmatter.getOrDefault("hidden", "false"));
        int maxSteps = parseInt(frontmatter.getOrDefault("steps",
                frontmatter.getOrDefault("maxSteps", "0")));
        if (maxSteps < 0) maxSteps = 0;

        List<String> toolAllow = parseStringList(frontmatter.get("toolAllow"), toolAllowBlock);
        if (toolAllow == null) toolAllow = parseStringList(frontmatter.get("tools.allow"), null);
        if (toolAllow == null && frontmatter.get("tools") != null) {
            toolAllow = parseJsonStringList(extractJson(frontmatter.get("tools"), "allow"));
        }
        if (toolAllow == null) toolAllow = List.of("*");

        List<String> toolDeny = parseStringList(frontmatter.get("toolDeny"), toolDenyBlock);
        if (toolDeny == null) toolDeny = parseStringList(frontmatter.get("tools.deny"), null);
        if (toolDeny == null && frontmatter.get("tools") != null) {
            toolDeny = parseJsonStringList(extractJson(frontmatter.get("tools"), "deny"));
        }
        if (toolDeny == null) toolDeny = List.of();

        List<String> mcpServers = parseStringList(frontmatter.get("mcpServers"), mcpServersBlock);
        if (mcpServers == null) mcpServers = List.of("*");

        List<String> roles = parseStringList(frontmatter.get("rolesAllowed"), rolesAllowedBlock);
        if (roles == null) roles = parseStringList(frontmatter.get("roles"), rolesBlock);
        if (roles == null) roles = parseStringList(frontmatter.get("roles-allowed"), rolesDashBlock);

        List<Rule> permissions = parsePermissions(frontmatter.get("permissions"), permissionsBlock);
        if (permissions.isEmpty()) {
            permissions = defaultRules();
        }

        String system = body.trim();
        return new EngineConfig.AgentConfig(name, system.isBlank() ? null : system, hidden, mode, model,
                maxSteps, toolAllow, toolDeny, permissions, mcpServers, roles);
    }

    private static List<Rule> parsePermissions(String inline, List<Map<String, String>> block) {
        List<Rule> result = new ArrayList<>();
        if (inline != null && !inline.isBlank()) {
            String value = unquote(inline.trim());
            if (value.startsWith("[")) {
                Result<JsonValue> parsed = Json.parse(value);
                if (parsed instanceof Result.Ok<JsonValue> ok && ok.value() instanceof JsonValue.Arr arr) {
                    for (JsonValue item : arr.values()) {
                        Rule rule = toRule(item);
                        if (rule != null) result.add(rule);
                    }
                }
            }
        }
        for (Map<String, String> item : block) {
            Rule rule = toRule(item);
            if (rule != null) result.add(rule);
        }
        return result;
    }

    private static Rule toRule(Object value) {
        if (value instanceof JsonValue.Obj obj) {
            String action = string(obj, "action");
            String resource = string(obj, "resource");
            String effect = string(obj, "effect");
            return toRule(action, resource, effect);
        }
        if (value instanceof Map<?, ?> map) {
            return toRule(str(map.get("action")), str(map.get("resource")), str(map.get("effect")));
        }
        return null;
    }

    private static Rule toRule(String action, String resource, String effect) {
        if (action == null || action.isBlank() || resource == null || resource.isBlank() || effect == null || effect.isBlank()) {
            return null;
        }
        try {
            return new Rule(action.trim(), resource.trim(), Effect.valueOf(effect.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String string(JsonValue.Obj obj, String key) {
        JsonValue value = obj.values().get(key);
        return value instanceof JsonValue.Str str ? str.value() : null;
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }

    private static List<String> parseStringList(String inline, List<String> block) {
        if (block != null && !block.isEmpty()) return List.copyOf(block);
        if (inline == null || inline.isBlank()) return null;
        String value = unquote(inline.trim());
        if (value.startsWith("[")) {
            Result<JsonValue> parsed = Json.parse(value);
            if (parsed instanceof Result.Ok<JsonValue> ok && ok.value() instanceof JsonValue.Arr arr) {
                List<String> result = new ArrayList<>();
                for (JsonValue item : arr.values()) {
                    if (item instanceof JsonValue.Str str) result.add(str.value());
                }
                return result.isEmpty() ? null : List.copyOf(result);
            }
        }
        List<String> result = new ArrayList<>();
        for (String part : value.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) result.add(trimmed);
        }
        return result.isEmpty() ? null : List.copyOf(result);
    }

    private static List<String> parseJsonStringList(String json) {
        if (json == null || json.isBlank()) return null;
        Result<JsonValue> parsed = Json.parse(json);
        if (parsed instanceof Result.Ok<JsonValue> ok && ok.value() instanceof JsonValue.Arr arr) {
            List<String> result = new ArrayList<>();
            for (JsonValue item : arr.values()) {
                if (item instanceof JsonValue.Str str) result.add(str.value());
            }
            return result.isEmpty() ? null : List.copyOf(result);
        }
        return null;
    }

    private static String extractJson(String value, String key) {
        if (value == null) return null;
        String trimmed = value.trim();
        if (!trimmed.startsWith("{")) return null;
        Result<JsonValue> parsed = Json.parse(trimmed);
        if (parsed instanceof Result.Ok<JsonValue> ok && ok.value() instanceof JsonValue.Obj obj) {
            JsonValue child = obj.values().get(key);
            return child == null ? null : Json.encode(child);
        }
        return null;
    }

    private static int parseInt(String value) {
        if (value == null || value.isBlank()) return 0;
        try {
            return Integer.parseInt(unquote(value).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String unquote(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        if (trimmed.length() >= 2
                && ((trimmed.startsWith("\"") && trimmed.endsWith("\""))
                || (trimmed.startsWith("'") && trimmed.endsWith("'")))) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static List<Rule> defaultRules() {
        List<Rule> rules = new ArrayList<>();
        rules.add(new Rule("*", "*", Effect.ALLOW));
        rules.add(new Rule("external_directory", "*", Effect.DENY));
        rules.add(new Rule("read", "*.env", Effect.ASK));
        rules.add(new Rule("edit", "*.env", Effect.DENY));
        rules.add(new Rule("shell", "ls", Effect.ALLOW));
        rules.add(new Rule("shell", "ls *", Effect.ALLOW));
        rules.add(new Rule("shell", "*", Effect.ASK));
        rules.add(new Rule("external_directory", EngineConfig.planDirectory(null) + "/*", Effect.ALLOW));
        return rules;
    }

    private record ParsedFrontmatter(Map<String, String> scalars,
                                     Map<String, List<String>> lists,
                                     List<Map<String, String>> permissions) {
        public Map<String, String> scalars() { return scalars; }
        public List<Map<String, String>> permissions() { return permissions; }
        public List<String> list(String key) { return lists.get(key); }
    }

    private static ParsedFrontmatter parseFrontmatter(String fm) {
        Map<String, String> scalars = new LinkedHashMap<>();
        Map<String, List<String>> lists = new LinkedHashMap<>();
        List<Map<String, String>> permissions = new ArrayList<>();

        String[] lines = fm.split("\n", -1);
        int i = 0;
        while (i < lines.length) {
            String line = lines[i];
            if (line.isBlank() || line.trim().startsWith("#")) { i++; continue; }
            int indent = leadingSpaces(line);
            if (indent != 0) { i++; continue; }
            String trimmed = line.trim();
            int colon = trimmed.indexOf(':');
            if (colon < 0) { i++; continue; }
            String key = trimmed.substring(0, colon).trim();
            String value = unquote(trimmed.substring(colon + 1).trim());

            if (value.isEmpty()) {
                // collect block
                List<String> block = new ArrayList<>();
                int j = i + 1;
                while (j < lines.length) {
                    String next = lines[j];
                    if (next.isBlank()) { block.add(next); j++; continue; }
                    if (leadingSpaces(next) <= indent) break;
                    block.add(next);
                    j++;
                }
                if (key.equalsIgnoreCase("permissions")) {
                    permissions.addAll(parsePermissionBlock(block));
                } else {
                    List<String> items = parseScalarBlock(block);
                    if (items != null) lists.put(key, items);
                }
                i = j;
            } else {
                scalars.put(key, value);
                i++;
            }
        }
        return new ParsedFrontmatter(scalars, lists, permissions);
    }

    private static List<Map<String, String>> parsePermissionBlock(List<String> block) {
        List<Map<String, String>> result = new ArrayList<>();
        Map<String, String> current = null;
        int dashIndent = -1;
        for (String raw : block) {
            if (raw.isBlank()) continue;
            int indent = leadingSpaces(raw);
            String trimmed = raw.trim();
            if (trimmed.startsWith("- ")) {
                current = new LinkedHashMap<>();
                result.add(current);
                dashIndent = indent;
                String rest = trimmed.substring(2).trim();
                if (!rest.isEmpty() && rest.contains(":")) {
                    int c = rest.indexOf(':');
                    current.put(rest.substring(0, c).trim(), unquote(rest.substring(c + 1).trim()));
                }
            } else if (current != null && indent > dashIndent && trimmed.contains(":")) {
                int c = trimmed.indexOf(':');
                current.put(trimmed.substring(0, c).trim(), unquote(trimmed.substring(c + 1).trim()));
            }
        }
        return result;
    }

    private static List<String> parseScalarBlock(List<String> block) {
        List<String> result = new ArrayList<>();
        boolean sawItem = false;
        for (String raw : block) {
            if (raw.isBlank()) continue;
            String trimmed = raw.trim();
            if (!trimmed.startsWith("- ")) return null;
            sawItem = true;
            String value = unquote(trimmed.substring(2).trim());
            if (!value.isEmpty()) result.add(value);
        }
        return sawItem ? result : null;
    }

    private static int leadingSpaces(String line) {
        int count = 0;
        while (count < line.length() && line.charAt(count) == ' ') count++;
        return count;
    }
}
