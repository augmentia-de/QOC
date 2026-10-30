package de.augmentia.oc.engine.http;

import de.augmentia.oc.engine.core.model.AgentId;
import de.augmentia.oc.engine.core.model.Effect;
import de.augmentia.oc.engine.core.model.Rule;
import de.augmentia.oc.engine.core.eval.PermissionRules;
import de.augmentia.oc.engine.runtime.context.AgentProfile;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class EngineAgentProfileTest {

    @Test
    void usesSystemPromptMaxStepsAndPermissionsFromAgentConfig() {
        Engine engine = new Engine();
        EngineConfig.AgentConfig config = new EngineConfig.AgentConfig(
                "researcher", "You are a research agent.", false, null, 7,
                List.of("read", "websearch"), List.of("shell"),
                List.of(new Rule("read", "*", Effect.ALLOW)), List.of("*"));

        AgentProfile profile = engine.agentProfile(AgentId.create(), "researcher", config);

        assertThat(profile.system().orElseThrow()).isEqualTo("You are a research agent.");
        assertThat(profile.maxSteps()).isEqualTo(7);
        assertThat(profile.permissions()).containsExactly(new Rule("read", "*", Effect.ALLOW));
    }

    @Test
    void zeroStepLimitMeansNoLimit() {
        Engine engine = new Engine();
        EngineConfig.AgentConfig config = new EngineConfig.AgentConfig(
                "researcher", "You are a research agent.", false, null, 0,
                List.of("read"), List.of(),
                List.of(new Rule("read", "*", Effect.ALLOW)), List.of("*"));

        AgentProfile profile = engine.agentProfile(AgentId.create(), "researcher", config);

        assertThat(profile.maxSteps()).isZero();
    }

    @Test
    void buildAgentWithScopedEnvEditDenyIsNotPlanMode() {
        Engine engine = new Engine();
        EngineConfig.AgentConfig build = new EngineConfig.AgentConfig("build", null, false);

        AgentProfile profile = engine.agentProfile(AgentId.create(), "build", build);

        assertThat(profile.planMode()).isFalse();
    }

    @Test
    void fallsBackToDefaultsWithoutAgentConfig() {
        Engine engine = new Engine();

        AgentProfile profile = engine.agentProfile(AgentId.create(), "custom", null);

        assertThat(profile.system().isEmpty()).isTrue();
        assertThat(profile.maxSteps()).isZero();
        assertThat(profile.permissions()).containsExactly(
                new Rule("*", "*", Effect.ALLOW),
                new Rule("grep", "*", Effect.ASK));
    }

    @Test
    void perAgentSkillAllowAndDenyBecomePermissionRules(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("settings.json"), """
                {"agents": [{"name": "plan", "skills": {
                    "allow": ["plan-*"], "deny": ["plan-secret"]}}]}
                """);
        EngineConfig config = new EngineConfig();
        config.workspaceProperty = workspace.toString();
        config.providerProperty = "openai";
        config.modelProperty = "gpt-4o-mini";
        config.urlProperty = "https://api.openai.com/v1/chat/completions";
        config.passwordProperty = Optional.empty();
        config.load();

        Engine engine = new Engine();
        engine.config = config;
        EngineConfig.AgentConfig agent = config.agents().stream()
                .filter(candidate -> candidate.name().equals("plan"))
                .findFirst()
                .orElseThrow();

        List<Rule> rules = engine.agentProfile(AgentId.create(), "plan", agent).permissions();

        assertThat(PermissionRules.evaluate(rules, "skill", "plan-review")).isEqualTo(Effect.ALLOW);
        assertThat(PermissionRules.evaluate(rules, "skill", "deploy")).isEqualTo(Effect.DENY);
        assertThat(PermissionRules.evaluate(rules, "skill", "plan-secret")).isEqualTo(Effect.DENY);
    }

    @Test
    void noPerAgentSkillBlockLeavesPermissionsUnchanged() {
        Engine engine = new Engine();
        EngineConfig.AgentConfig config = new EngineConfig.AgentConfig("plan", null, false,
                "all", null, 0, List.of("*"), List.of(), List.of(), List.of("*"));

        AgentProfile profile = engine.agentProfile(AgentId.create(), "plan", config);

        assertThat(profile.permissions()).containsExactly(
                new Rule("*", "*", Effect.ALLOW),
                new Rule("grep", "*", Effect.ASK));
    }
}
