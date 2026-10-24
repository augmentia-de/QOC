package de.augmentia.oc.engine.runtime;

import de.augmentia.oc.engine.core.contract.tool.ToolContext;
import de.augmentia.oc.engine.core.contract.tool.ToolOutcome;
import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.model.AgentId;
import de.augmentia.oc.engine.core.model.CallId;
import de.augmentia.oc.engine.core.model.Effect;
import de.augmentia.oc.engine.core.model.MessageId;
import de.augmentia.oc.engine.core.model.Rule;
import de.augmentia.oc.engine.core.model.SessionId;
import de.augmentia.oc.engine.core.model.SkillId;
import de.augmentia.oc.engine.runtime.skill.Skill;
import de.augmentia.oc.engine.runtime.skill.SkillCatalog;
import de.augmentia.oc.engine.runtime.tool.skill.SkillTool;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SkillToolTest {

    @Test
    void loadsTheSkillContent() {
        SkillCatalog catalog = SkillCatalog.of(List.of(skill("commit", "use when committing")));
        ToolOutcome outcome = execute(catalog, List.of(), "commit");

        assertThat(outcome).isInstanceOf(ToolOutcome.Ok.class);
        assertThat(text(outcome)).contains("<skill_content name=\"commit\">").contains("# Skill: commit");
    }

    @Test
    void unknownSkillIsAToolError() {
        assertThat(execute(SkillCatalog.empty(), List.of(), "missing"))
                .isInstanceOf(ToolOutcome.Failed.class);
    }

    @Test
    void deniedSkillIsAToolError() {
        SkillCatalog catalog = SkillCatalog.of(List.of(skill("commit", "commit")));
        ToolOutcome outcome = execute(catalog, List.of(new Rule("skill", "commit", Effect.DENY)), "commit");

        assertThat(outcome).isInstanceOf(ToolOutcome.Failed.class);
        assertThat(((ToolOutcome.Failed) outcome).error().message()).contains("not allowed");
    }

    @Test
    void skillWithoutDescriptionIsStillLoadable() {
        SkillCatalog catalog = SkillCatalog.of(List.of(skill("hidden", "")));
        assertThat(execute(catalog, List.of(), "hidden")).isInstanceOf(ToolOutcome.Ok.class);
    }

    @Test
    void rulesAreResolvedPerCallingAgent() {
        SkillCatalog catalog = SkillCatalog.of(List.of(skill("commit", "commit")));
        AgentId deniedAgent = AgentId.create();
        AgentId allowedAgent = AgentId.create();
        var definition = SkillTool.definition(catalog, agentId ->
                agentId.equals(deniedAgent)
                        ? List.of(new Rule("skill", "commit", Effect.DENY))
                        : List.of());

        assertThat(execute(definition, deniedAgent, "commit")).isInstanceOf(ToolOutcome.Failed.class);
        assertThat(execute(definition, allowedAgent, "commit")).isInstanceOf(ToolOutcome.Ok.class);
    }

    private static ToolOutcome execute(SkillCatalog catalog, List<Rule> rules, String id) {
        ToolContext context = new ToolContext(SessionId.create(), AgentId.create(),
                MessageId.create(), CallId.create());
        return SkillTool.definition(catalog, rules).execute().execute(
                JsonValue.of(Map.of("id", JsonValue.of(id))), context);
    }

    private static ToolOutcome execute(de.augmentia.oc.engine.core.contract.tool.ToolDefinition definition,
                                       AgentId agent, String id) {
        ToolContext context = new ToolContext(SessionId.create(), agent,
                MessageId.create(), CallId.create());
        return definition.execute().execute(JsonValue.of(Map.of("id", JsonValue.of(id))), context);
    }

    private static Skill skill(String id, String description) {
        return new Skill(SkillId.parse(id), id, description, "body", Path.of("/skills", id),
                List.of(), true);
    }

    private static String text(ToolOutcome outcome) {
        return ((de.augmentia.oc.engine.core.model.ToolContent.TextItem)
                ((ToolOutcome.Ok) outcome).result().content().getFirst()).text();
    }
}
