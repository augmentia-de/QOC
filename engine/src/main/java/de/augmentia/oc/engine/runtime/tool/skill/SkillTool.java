package de.augmentia.oc.engine.runtime.tool.skill;

import de.augmentia.oc.engine.core.contract.tool.ToolDefinition;
import de.augmentia.oc.engine.core.contract.tool.ToolOptions;
import de.augmentia.oc.engine.core.contract.tool.ToolOutcome;
import de.augmentia.oc.engine.core.contract.tool.ToolResult;
import de.augmentia.oc.engine.core.eval.PermissionRules;
import de.augmentia.oc.engine.core.eval.PromptLoader;
import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.json.Schema;
import de.augmentia.oc.engine.core.model.Effect;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.Rule;
import de.augmentia.oc.engine.core.model.AgentId;
import de.augmentia.oc.engine.core.model.SkillId;
import de.augmentia.oc.engine.core.model.ToolContent;
import de.augmentia.oc.engine.core.model.ToolName;
import de.augmentia.oc.engine.runtime.skill.Skill;
import de.augmentia.oc.engine.runtime.skill.SkillCatalog;
import de.augmentia.oc.engine.runtime.tool.JsonArgs;
import de.augmentia.oc.engine.runtime.tool.fs.ReadTool;

import java.util.List;
import java.util.Map;

/** {@code skill}: loads text, never executes it (SK-01/SK-08/SK-09). */
public final class SkillTool {

    private SkillTool() {}

    public static ToolDefinition definition(SkillCatalog catalog, List<Rule> rules) {
        if (catalog == null) throw new IllegalArgumentException("catalog must not be null");
        List<Rule> safeRules = rules == null ? List.of() : rules;
        Schema input = Schema.obj(Map.of("id", Schema.str()));
        return new ToolDefinition(new ToolName("skill"), "Load a skill by ID",
                input, Option.none(),
                (value, context) -> execute(catalog, safeRules, value), null);
    }

    /**
     * Resolves the rules per calling agent so a {@code skill} deny hides and
     * blocks the skill for that agent (SK-06/SK-08).
     */
    public static ToolDefinition definition(SkillCatalog catalog,
                                            java.util.function.Function<AgentId, List<Rule>> rulesByAgent) {
        if (catalog == null) throw new IllegalArgumentException("catalog must not be null");
        java.util.function.Function<AgentId, List<Rule>> resolver =
                rulesByAgent == null ? agentId -> List.of() : rulesByAgent;
        Schema input = Schema.obj(Map.of("id", Schema.str()));
        return new ToolDefinition(new ToolName("skill"), "Load a skill by ID",
                input, Option.none(),
                (value, context) -> {
                    List<Rule> rules = resolver.apply(context.agentId());
                    return execute(catalog, rules == null ? List.of() : rules, value);
                }, null);
    }

    /**
     * Lists every catalog entry, including skills hidden from the prompt via
     * {@code disable-model-invocation}. A {@code skill} deny for the calling
     * agent also hides the entry (SK-08).
     */
    public static ToolDefinition listDefinition(SkillCatalog catalog,
                                                java.util.function.Function<AgentId, List<Rule>> rulesByAgent) {
        if (catalog == null) throw new IllegalArgumentException("catalog must not be null");
        java.util.function.Function<AgentId, List<Rule>> resolver =
                rulesByAgent == null ? agentId -> List.of() : rulesByAgent;
        Schema input = Schema.obj(Map.of());
        return new ToolDefinition(new ToolName("skill_list"),
                "List every available skill, including hidden ones. Load one with skill(id).",
                input, Option.none(),
                (value, context) -> {
                    List<Rule> rules = resolver.apply(context.agentId());
                    return list(catalog, rules == null ? List.of() : rules);
                },
                new ToolOptions(Option.none(), Option.of("skill"), true, false));
    }

    static ToolOutcome list(SkillCatalog catalog, List<Rule> rules) {
        List<Skill> visible = catalog.all().stream()
                .filter(skill -> PermissionRules.evaluate(rules, "skill", skill.id().value()) != Effect.DENY)
                .toList();
        if (visible.isEmpty()) {
            return new ToolOutcome.Ok(new ToolResult(Option.none(),
                    List.of(new ToolContent.TextItem("No skills are available.")),
                    Map.of("count", 0, "listed", 0)));
        }
        StringBuilder text = new StringBuilder();
        for (Skill skill : visible) {
            text.append("- ").append(skill.id().value());
            if (!skill.listed()) text.append(" (hidden)");
            text.append(": ").append(skill.description()).append('\n');
        }
        long listed = visible.stream().filter(Skill::listed).count();
        return new ToolOutcome.Ok(new ToolResult(Option.none(),
                List.of(new ToolContent.TextItem(text.toString().strip())),
                Map.of("count", visible.size(), "listed", listed)));
    }

    static ToolOutcome execute(SkillCatalog catalog, List<Rule> rules, JsonValue value) {
        try {
            String id = JsonArgs.requiredString(value, "id");
            if (PermissionRules.evaluate(rules, "skill", id) == Effect.DENY) {
                return ReadTool.failure("skill", "skill is not allowed: " + id);
            }
            Option<Skill> skill;
            try {
                skill = catalog.find(SkillId.parse(id));
            } catch (IllegalArgumentException e) {
                return ReadTool.failure("skill", PromptLoader.render("unable-to-load-skill", Map.of("id", id)));
            }
            if (skill.isEmpty()) {
                return ReadTool.failure("skill", PromptLoader.render("unable-to-load-skill", Map.of("id", id)));
            }
            return new ToolOutcome.Ok(new ToolResult(Option.none(),
                    List.of(new ToolContent.TextItem(catalog.content(skill.orElseThrow()))),
                    Map.of("skill", id)));
        } catch (IllegalArgumentException e) {
            return ReadTool.failure("skill", e.getMessage());
        }
    }
}
