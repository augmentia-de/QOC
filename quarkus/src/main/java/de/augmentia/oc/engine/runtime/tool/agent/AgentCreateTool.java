package de.augmentia.oc.engine.runtime.tool.agent;

import de.augmentia.oc.engine.core.contract.permission.PermissionGate;
import de.augmentia.oc.engine.core.contract.tool.ToolDefinition;
import de.augmentia.oc.engine.core.contract.tool.ToolOutcome;
import de.augmentia.oc.engine.core.contract.tool.ToolResult;
import de.augmentia.oc.engine.runtime.tool.JsonArgs;
import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.model.Rule;
import de.augmentia.oc.engine.core.model.Effect;
import de.augmentia.oc.engine.core.json.Schema;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.ToolContent;
import de.augmentia.oc.engine.core.model.ToolName;
import de.augmentia.oc.engine.http.AgentCatalogue;
import de.augmentia.oc.engine.http.EngineConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** {@code agent_create}: defines a new agent at runtime so it can be used as a subagent. */
public final class AgentCreateTool {

    private AgentCreateTool() {}

    public static ToolDefinition definition(AgentCatalogue catalogue, PermissionGate permissionGate) {
        Schema input = Schema.obj(Map.of(
                "name", Schema.str(),
                "system", Schema.opt(Schema.str()),
                "mode", Schema.opt(Schema.str()),
                "hidden", Schema.opt(Schema.bool()),
                "maxSteps", Schema.opt(Schema.num()),
                "toolAllow", Schema.opt(Schema.arr(Schema.str())),
                "toolDeny", Schema.opt(Schema.arr(Schema.str())),
                "skills", Schema.opt(Schema.obj(Map.of(), true))));
        return new ToolDefinition(new ToolName("agent_create"),
                "Define a new agent at runtime (available as a subagent afterwards)",
                input, Option.none(),
                (value, context) -> execute(catalogue, value), null);
    }

    @SuppressWarnings("unchecked")
    private static ToolOutcome execute(AgentCatalogue catalogue, JsonValue value) {
        try {
            String name = JsonArgs.requiredString(value, "name");
            String system = JsonArgs.string(value, "system");
            String mode = JsonArgs.string(value, "mode");
            if (mode == null || mode.isBlank()) mode = "subagent";
            boolean hidden = JsonArgs.boolOr(value, "hidden", true);
            int maxSteps = (int) JsonArgs.longOr(value, "maxSteps", 0L);
            List<String> allow = stringList(JsonArgs.field(value, "toolAllow"));
            if (allow.isEmpty()) allow = List.of("*");
            List<String> deny = stringList(JsonArgs.field(value, "toolDeny"));

            EngineConfig.AgentSkillsConfig skills = parseSkills(JsonArgs.field(value, "skills"));

            EngineConfig.AgentConfig config = new EngineConfig.AgentConfig(
                    name, system, hidden, mode, null, maxSteps, allow, deny,
                    List.of(new de.augmentia.oc.engine.core.model.Rule("*", "*", de.augmentia.oc.engine.core.model.Effect.ALLOW)),
                    List.of("*"));

            catalogue.define(name, config, skills);
            return new ToolOutcome.Ok(new ToolResult(Option.none(),
                    List.of(new ToolContent.TextItem("created agent: " + name)), Map.of("agent", name)));
        } catch (IllegalArgumentException e) {
            return new ToolOutcome.Failed(
                    new de.augmentia.oc.engine.core.model.SessionError.ToolExecutionError("agent_create", e.getMessage()),
                    Map.of());
        } catch (RuntimeException e) {
            return new ToolOutcome.Failed(
                    new de.augmentia.oc.engine.core.model.SessionError.ToolExecutionError("agent_create", e.getMessage()),
                    Map.of());
        }
    }

    private static List<String> stringList(JsonValue value) {
        if (!(value instanceof JsonValue.Arr a)) return List.of();
        List<String> out = new ArrayList<>();
        for (JsonValue item : a.values()) {
            if (item instanceof JsonValue.Str s) out.add(s.value());
        }
        return out;
    }

    private static EngineConfig.AgentSkillsConfig parseSkills(JsonValue value) {
        if (!(value instanceof JsonValue.Obj obj)) return null;
        Map<String, JsonValue> v = obj.values();
        boolean enabled = bool(v, "enabled", true);
        List<String> allow = stringList(v, "allow");
        List<String> deny = stringList(v, "deny");
        int max = (int) longVal(v, "max", 0);
        String prompt = string(v, "prompt");
        if (prompt == null || prompt.isBlank()) prompt = "top-k";
        return new EngineConfig.AgentSkillsConfig(enabled, allow, deny, max, prompt, List.of(), null);
    }

    private static boolean bool(Map<String, JsonValue> v, String key, boolean d) {
        JsonValue val = v.get(key);
        return val instanceof JsonValue.Bool b ? b.value() : d;
    }
    private static String string(Map<String, JsonValue> v, String key) {
        JsonValue val = v.get(key);
        return val instanceof JsonValue.Str s ? s.value() : null;
    }
    private static List<String> stringList(Map<String, JsonValue> v, String key) {
        JsonValue val = v.get(key);
        if (!(val instanceof JsonValue.Arr a)) return List.of();
        List<String> out = new ArrayList<>();
        for (JsonValue item : a.values()) {
            if (item instanceof JsonValue.Str s) out.add(s.value());
        }
        return out;
    }
    private static long longVal(Map<String, JsonValue> v, String key, long d) {
        JsonValue val = v.get(key);
        return val instanceof JsonValue.Num n ? n.value().longValue() : d;
    }
}