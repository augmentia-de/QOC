package de.augmentia.oc.engine.http;

import de.augmentia.oc.engine.core.error.Defect;
import de.augmentia.oc.engine.core.eval.PermissionRules;
import de.augmentia.oc.engine.core.model.Effect;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EngineConfigTest {

    @TempDir
    Path workspace;

    @Test
    void projectFileOverridesDefaultsAndMasksThePassword() throws Exception {
        Files.writeString(workspace.resolve("settings.json"), """
                {
                  "model": {"provider": "openai", "id": "gpt-4o"},
                  "provider": {"url": "https://example.test/v1"},
                  "auth": {"password": "secret"},
                  "agents": [{"name": "plan", "hidden": true}, {"name": "build"}]
                }
                """);

        EngineConfig config = config();
        config.load();

        assertThat(config.provider()).isEqualTo("openai");
        assertThat(config.model()).isEqualTo("gpt-4o");
        assertThat(config.url()).isEqualTo("https://example.test/v1");
        assertThat(config.password()).isEqualTo("secret");
        assertThat(config.agents()).hasSize(2);
        assertThat(config.agents().getFirst().name()).isEqualTo("plan");
        assertThat(config.entries())
                .filteredOn(entry -> entry.key().equals("auth.password"))
                .singleElement()
                .extracting(EngineConfig.ConfigEntry::value)
                .isEqualTo("***");
    }

    @Test
    void missingFileKeepsTheConfiguredDefaults() {
        EngineConfig config = config();
        config.load();

        assertThat(config.provider()).isEqualTo("openai");
        assertThat(config.model()).isEqualTo("gpt-4o-mini");
        assertThat(config.agents()).extracting(EngineConfig.AgentConfig::name)
                .containsExactly("build", "plan");
        assertThat(config.agents()).allMatch(agent -> agent.maxSteps() == 0);
        assertThat(config.skillsEnabled()).isTrue();
        assertThat(config.skillPaths()).isEmpty();
    }

    @Test
    void parsesSkillSettings() throws Exception {
        Files.writeString(workspace.resolve("settings.json"), """
                {"skills": {"enabled": false, "paths": ["/opt/skills", "extra"],
                            "allow": ["aisdlc-*"], "deny": ["*-qa"], "max": 12}}
                """);

        EngineConfig config = config();
        config.load();

        assertThat(config.skillsEnabled()).isFalse();
        assertThat(config.skillPaths()).containsExactly("/opt/skills", "extra");
        assertThat(config.skillAllow()).containsExactly("aisdlc-*");
        assertThat(config.skillDeny()).containsExactly("*-qa");
        assertThat(config.skillMax()).isEqualTo(12);
    }

    @Test
    void customAgentsKeepTheBuildAndPlanBaseline() throws Exception {
        Files.writeString(workspace.resolve("settings.json"), """
                {"agents": [{"name": "custom-role"}]}
                """);

        EngineConfig config = config();
        config.load();

        assertThat(config.agents()).extracting(EngineConfig.AgentConfig::name)
                .containsExactly("custom-role", "build", "plan");
    }

    @Test
    void stepLimitIsOptionalAndStepsWinsOverTheLegacyName() throws Exception {
        Files.writeString(workspace.resolve("settings.json"), """
                {"agents": [
                  {"name": "limited", "steps": 5},
                  {"name": "legacy", "maxSteps": 7},
                  {"name": "both", "steps": 5, "maxSteps": 7},
                  {"name": "unlimited"},
                  {"name": "zero", "steps": 0}
                ]}
                """);

        EngineConfig config = config();
        config.load();

        assertThat(agent(config, "limited").maxSteps()).isEqualTo(5);
        assertThat(agent(config, "legacy").maxSteps()).isEqualTo(7);
        assertThat(agent(config, "both").maxSteps()).isEqualTo(5);
        assertThat(agent(config, "unlimited").maxSteps()).isZero();
        assertThat(agent(config, "zero").maxSteps()).isZero();
    }

    @Test
    void defaultAgentsMayOnlyReachOutsideTheWorkspaceThroughThePlanDirectory() {
        EngineConfig config = config();
        config.load();

        String planFile = EngineConfig.planDirectory() + "/my-plan.md";
        for (EngineConfig.AgentConfig agent : config.agents()) {
            assertThat(PermissionRules.evaluate(agent.permissions(), "external_directory", planFile))
                    .as("plan directory for agent %s", agent.name())
                    .isEqualTo(Effect.ALLOW);
            assertThat(PermissionRules.evaluate(agent.permissions(), "external_directory", "/etc/passwd"))
                    .as("other external path for agent %s", agent.name())
                    .isEqualTo(Effect.DENY);
        }
    }

    @Test
    void parsesLocalAndRemoteMcpServers() throws Exception {
        Files.writeString(workspace.resolve("settings.json"), """
                {
                  "mcp": {
                    "github": {"type": "remote", "url": "https://example.test/mcp", "codemode": false,
                               "headers": {"x-token": "abc"}},
                    "fs": {"type": "local", "command": ["mcp-fs", "--root", "."],
                           "environment": {"A": "B"}}
                  }
                }
                """);

        EngineConfig config = config();
        config.load();

        assertThat(config.mcpServers()).hasSize(2);
        var github = (de.augmentia.oc.engine.runtime.tool.mcp.McpServerConfig.Remote)
                config.mcpServers().stream().filter(s -> s.name().equals("github")).findFirst().orElseThrow();
        assertThat(github.url()).isEqualTo("https://example.test/mcp");
        assertThat(github.codemode()).isFalse();
        assertThat(github.headers()).containsEntry("x-token", "abc");

        var fs = (de.augmentia.oc.engine.runtime.tool.mcp.McpServerConfig.Local)
                config.mcpServers().stream().filter(s -> s.name().equals("fs")).findFirst().orElseThrow();
        assertThat(fs.command()).containsExactly("mcp-fs", "--root", ".");
        assertThat(fs.environment()).containsEntry("A", "B");
    }

    @Test
    void invalidJsonIsADefect() throws Exception {
        Files.writeString(workspace.resolve("settings.json"), "{not json");
        EngineConfig config = config();
        assertThatThrownBy(config::load).isInstanceOf(Defect.class);
    }

    private EngineConfig config() {
        EngineConfig config = new EngineConfig();
        config.workspaceProperty = workspace.toString();
        config.providerProperty = "openai";
        config.modelProperty = "gpt-4o-mini";
        config.urlProperty = "https://api.openai.com/v1/chat/completions";
        config.passwordProperty = Optional.empty();
        return config;
    }

    private static EngineConfig.AgentConfig agent(EngineConfig config, String name) {
        return config.agents().stream()
                .filter(agent -> name.equals(agent.name()))
                .findFirst()
                .orElseThrow();
    }
}
