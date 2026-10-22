package de.augmentia.oc.engine.http;

import de.augmentia.oc.engine.core.error.Defect;

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
        Files.writeString(workspace.resolve("opencode.json"), """
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
        assertThat(config.agents()).extracting(EngineConfig.AgentConfig::name).containsExactly("build");
    }

    @Test
    void invalidJsonIsADefect() throws Exception {
        Files.writeString(workspace.resolve("opencode.json"), "{not json");
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
}
