package de.augmentia.oc.engine.http;

import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.json.JsonValues;
import de.augmentia.oc.engine.runtime.skill.SkillFilterOptions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AgentAccessTest {

    @Test
    void baselineAgentsAreOpenToEveryone() {
        EngineConfig.AgentConfig build = new EngineConfig.AgentConfig("build", null, false);
        EngineConfig.AgentConfig plan = new EngineConfig.AgentConfig("plan", null, false);

        assertThat(build.rolesAllowed()).containsExactly("*");
        assertThat(plan.rolesAllowed()).containsExactly("*");
        assertThat(AgentAccess.allowed(build, List.of())).isTrue();
        assertThat(AgentAccess.allowed(plan, List.of("qoc-user"))).isTrue();
    }

    @Test
    void customAgentsDefaultToAdmin() {
        EngineConfig.AgentConfig agent = new EngineConfig.AgentConfig("research", null, false);

        assertThat(agent.rolesAllowed()).containsExactly("admin");
        assertThat(AgentAccess.allowed(agent, List.of("qoc-user"))).isFalse();
        assertThat(AgentAccess.allowed(agent, List.of("admin"))).isTrue();
        assertThat(AgentAccess.allowed(agent, List.of("qoc-admin"))).isTrue();
    }

    @Test
    void explicitRolesOverrideTheDefault() {
        EngineConfig.AgentConfig agent = new EngineConfig.AgentConfig("research", null, false,
                "all", null, 0, List.of("*"), List.of(), List.of(), List.of("*"),
                List.of("qoc-user", "mcp-user"));

        assertThat(agent.rolesAllowed()).containsExactly("qoc-user", "mcp-user");
        assertThat(AgentAccess.allowed(agent, List.of("qoc-user"))).isTrue();
        assertThat(AgentAccess.allowed(agent, List.of("mcp-user"))).isTrue();
        assertThat(AgentAccess.allowed(agent, List.of("admin"))).isFalse();
    }

    @Test
    void markdownFrontmatterParsesRoles() {
        String markdown = """
                ---
                name: research
                roles:
                  - qoc-user
                  - mcp-user
                ---
                Research body.
                """;

        EngineConfig.AgentConfig agent = AgentMarkdownLoader.parseContent(markdown, "research");

        assertThat(agent).isNotNull();
        assertThat(agent.rolesAllowed()).containsExactly("qoc-user", "mcp-user");
    }

    @Test
    void markdownScalarRolesAllowedAliasIsParsed() {
        String markdown = """
                ---
                name: research
                rolesAllowed: qoc-user, mcp-user
                ---
                Research body.
                """;

        EngineConfig.AgentConfig agent = AgentMarkdownLoader.parseContent(markdown, "research");

        assertThat(agent).isNotNull();
        assertThat(agent.rolesAllowed()).containsExactly("qoc-user", "mcp-user");
    }

    @Test
    void markdownDefaultsForNonBaselineAgent() {
        String markdown = """
                ---
                name: research
                ---
                Research body.
                """;

        EngineConfig.AgentConfig agent = AgentMarkdownLoader.parseContent(markdown, "research");

        assertThat(agent).isNotNull();
        assertThat(agent.rolesAllowed()).containsExactly("admin");
    }

    @Test
    void agentJsonContainsRolesAndSkillFilter() {
        EngineConfig.AgentConfig agent = new EngineConfig.AgentConfig("research", null, false,
                "all", null, 0, List.of("*"), List.of(), List.of(), List.of("*"),
                List.of("admin"));
        EngineConfig.AgentSkillsConfig skills = new EngineConfig.AgentSkillsConfig(
                true, List.of(), List.of(), 0, "top-k", List.of(),
                new SkillFilterOptions(true, "bm25", 8, 0.3, 10, List.of("core")));

        JsonValue.Obj json = EngineConfig.agentToJson(agent, skills);
        Map<String, Object> java = (Map<String, Object>) JsonValues.toJava(json);

        assertThat(java).containsEntry("rolesAllowed", List.of("admin"));
        Map<String, Object> skillsJson = (Map<String, Object>) java.get("skills");
        assertThat(skillsJson).containsKey("filter");
        Map<String, Object> filter = (Map<String, Object>) skillsJson.get("filter");
        assertThat(filter).containsEntry("strategy", "bm25");
        assertThat(((Number) filter.get("topK")).intValue()).isEqualTo(8);
    }
}
