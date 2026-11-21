package de.augmentia.oc.engine.http;

import de.augmentia.qoc.workflowrunner.model.WorkflowDefinition.Agent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AgentMarkdownExporterTest {

    @TempDir
    Path dir;

    @Test
    void roundTripParse() throws Exception {
        Agent a = new Agent("my-agent", "My Agent", "1.0.0", "core", "gpt-4o",
                0.3, 4096, 1.0, List.of("bash"), List.of(), List.of(), List.of(),
                false, true, "You are helpful.", null, "", List.of("admin"), "all", List.of(),
                100, 1.0, false, List.of());
        AgentMarkdownExporter.write(dir, a);
        Path file = dir.resolve("my-agent.md");
        assertThat(Files.exists(file)).isTrue();
        String content = Files.readString(file);
        assertThat(content).contains("name: My Agent");
        assertThat(content).contains("You are helpful.");
        // Round-trip through loader
        EngineConfig.AgentConfig parsed = AgentMarkdownLoader.parseContent(content, "my-agent");
        assertThat(parsed).isNotNull();
        assertThat(parsed.name()).isEqualTo("My Agent");
        assertThat(parsed.system()).isEqualTo("You are helpful.");
        assertThat(parsed.model()).isEqualTo("gpt-4o");
    }
}
