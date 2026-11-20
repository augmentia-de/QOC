package de.augmentia.qoc.workflowrunner.validation;

import de.augmentia.qoc.workflowrunner.model.WorkflowDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GraphCompilerTest {

    private static WorkflowDefinition.NodeData agentData(String ref) {
        return new WorkflowDefinition.NodeData("Context Agent", ref, false, "human", 3, "",
                List.of(), "all", null, List.of(), "", "", null, "", "", Map.of(), null);
    }

    @Test
    void contextAgentExecutorIsValidWithAgentRef() {
        WorkflowDefinition def = new WorkflowDefinition(
                "wf", "W", "1.0.0", "draft",
                List.of(
                        new WorkflowDefinition.Node("start", "start",
                                new WorkflowDefinition.Point(0, 0), agentData("")),
                        new WorkflowDefinition.Node("ca", "context_agent_executor",
                                new WorkflowDefinition.Point(10, 0), agentData("a1")),
                        new WorkflowDefinition.Node("end", "end",
                                new WorkflowDefinition.Point(20, 0), agentData(""))
                ),
                List.of(
                        new WorkflowDefinition.Edge("e1", "start", "ca", "default", ""),
                        new WorkflowDefinition.Edge("e2", "ca", "end", "default", "")
                ),
                List.of(new WorkflowDefinition.Agent("a1", "A", "1.0.0", "", "m", 0.3, 4096,
                        1.0, List.of(), List.of(), List.of(), List.of(), false, true,
                        "", null, "", List.of("*"), "all", List.of(), 10, 1.0, false, java.util.List.of())),
                "",
                "");

        GraphCompiler.ValidationResult result = GraphCompiler.validate(def);
        assertTrue(result.valid(), () -> String.join("; ", result.errors()));
    }

    @Test
    void contextAgentExecutorWithoutAgentRefIsInvalid() {
        WorkflowDefinition def = new WorkflowDefinition(
                "wf", "W", "1.0.0", "draft",
                List.of(
                        new WorkflowDefinition.Node("start", "start",
                                new WorkflowDefinition.Point(0, 0), agentData("")),
                        new WorkflowDefinition.Node("ca", "context_agent_executor",
                                new WorkflowDefinition.Point(10, 0), agentData("")),
                        new WorkflowDefinition.Node("end", "end",
                                new WorkflowDefinition.Point(20, 0), agentData(""))
                ),
                List.of(
                        new WorkflowDefinition.Edge("e1", "start", "ca", "default", ""),
                        new WorkflowDefinition.Edge("e2", "ca", "end", "default", "")
                ),
                List.of(),
                "",
                "");

        GraphCompiler.ValidationResult result = GraphCompiler.validate(def);
        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("requires agentRef")));
    }

    @Test
    void contextAgentExecutorWithUnknownRefIsInvalid() {
        WorkflowDefinition def = new WorkflowDefinition(
                "wf", "W", "1.0.0", "draft",
                List.of(
                        new WorkflowDefinition.Node("start", "start",
                                new WorkflowDefinition.Point(0, 0), agentData("")),
                        new WorkflowDefinition.Node("ca", "context_agent_executor",
                                new WorkflowDefinition.Point(10, 0), agentData("ghost")),
                        new WorkflowDefinition.Node("end", "end",
                                new WorkflowDefinition.Point(20, 0), agentData(""))
                ),
                List.of(
                        new WorkflowDefinition.Edge("e1", "start", "ca", "default", ""),
                        new WorkflowDefinition.Edge("e2", "ca", "end", "default", "")
                ),
                List.of(),
                "",
                "");

        GraphCompiler.ValidationResult result = GraphCompiler.validate(def);
        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("not found in agents[]")));
    }
}
