package de.augmentia.oc.engine.http;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class AgentCatalogueTest {

    @TempDir
    Path workspaceA;

    @TempDir
    Path workspaceB;

    private String previousHome;

    @BeforeEach
    void isolateUserHome() {
        previousHome = System.getProperty("user.home");
        System.setProperty("user.home", workspaceA.toString());
    }

    @AfterEach
    void restoreUserHome() {
        if (previousHome != null) {
            System.setProperty("user.home", previousHome);
        }
    }

    @Test
    void workspaceSwitchDropsPreviousWorkspaceAgentsButKeepsRuntimeAgents() throws Exception {
        Files.writeString(workspaceA.resolve("settings.json"), """
                {"agents": [{"name": "alpha"}]}
                """);
        Files.writeString(workspaceB.resolve("settings.json"), """
                {"agents": [{"name": "beta"}]}
                """);

        EngineConfig config = config(workspaceA);
        config.load();
        AgentCatalogue catalogue = new AgentCatalogue();
        catalogue.config = config;
        catalogue.load();

        assertThat(names(catalogue)).contains("alpha", "build", "plan");
        assertThat(names(catalogue)).doesNotContain("beta");

        catalogue.define("runtime-agent",
                new EngineConfig.AgentConfig("runtime-agent", null, false), null);

        config.setWorkspace(workspaceB.toString());
        config.load();
        catalogue.reload();

        assertThat(names(catalogue)).contains("beta", "build", "plan", "runtime-agent");
        assertThat(names(catalogue)).doesNotContain("alpha");
    }

    private static EngineConfig config(Path workspace) {
        EngineConfig config = new EngineConfig();
        config.workspaceProperty = workspace.toString();
        config.providerProperty = "openai";
        config.modelProperty = "gpt-4o-mini";
        config.urlProperty = "https://api.openai.com/v1/chat/completions";
        config.passwordProperty = Optional.empty();
        config.workspaceSettingsEnabledProperty = true;
        config.modeProperty = "server";
        config.agentSourceUrlProperty = Optional.empty();
        config.agentAuthoringEnabledProperty = true;
        config.agentSourceTokenProperty = Optional.empty();
        config.globalSettingsFileProperty =
                Optional.of(workspace.resolve("global-settings.json").toString());
        return config;
    }

    private static java.util.List<String> names(AgentCatalogue catalogue) {
        return catalogue.list().stream().map(AgentCatalogue.AgentDto::name).toList();
    }
}
