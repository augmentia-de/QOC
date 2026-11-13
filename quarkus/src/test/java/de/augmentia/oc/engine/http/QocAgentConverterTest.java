package de.augmentia.oc.engine.http;

import de.augmentia.qoc.workflowrunner.model.WorkflowDefinition.Agent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class QocAgentConverterTest {

    @Test
    void convertsConfigToWorkflowAgent() {
        EngineConfig.AgentConfig cfg = new EngineConfig.AgentConfig(
                "My Agent", "You are helpful.", false, "subagent", "gpt-4o",
                5, List.of("bash", "read"), List.of(), List.of(), List.of("*"), List.of("admin"));
        Agent a = QocAgentConverter.fromQoc(cfg);
        assertThat(a).isNotNull();
        assertThat(a.name()).isEqualTo("My Agent");
        assertThat(a.id()).isEqualTo("my-agent");
        assertThat(a.systemPrompt()).isEqualTo("You are helpful.");
        assertThat(a.model()).isEqualTo("gpt-4o");
        assertThat(a.mode()).isEqualTo("subagent");
        assertThat(a.active()).isTrue();
        assertThat(a.tools()).containsExactly("bash", "read");
        assertThat(a.rolesAllowed()).containsExactly("admin");
    }

    @Test
    void hiddenAgentIsActiveFalse() {
        EngineConfig.AgentConfig cfg = new EngineConfig.AgentConfig(
                "Hidden", "sys", true, "all", null, 0, List.of("*"), List.of(), List.of(), List.of("*"), null);
        Agent a = QocAgentConverter.fromQoc(cfg);
        assertThat(a.active()).isFalse();
        assertThat(a.category()).isEqualTo("hidden");
        assertThat(a.rolesAllowed()).containsExactly("admin");
    }

    @Test
    void nullConfigReturnsNull() {
        assertThat(QocAgentConverter.fromQoc(null)).isNull();
    }
}
