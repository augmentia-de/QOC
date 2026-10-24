package de.augmentia.oc.engine.runtime.tool.skill;

import de.augmentia.oc.engine.core.contract.tool.ToolDefinition;
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
