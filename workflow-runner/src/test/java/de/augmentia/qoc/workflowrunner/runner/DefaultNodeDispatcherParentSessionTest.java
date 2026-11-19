package de.augmentia.qoc.workflowrunner.runner;

import de.augmentia.oc.engine.adapter.memory.MemoryStores;
import de.augmentia.oc.engine.adapter.memory.RuntimeState;
import de.augmentia.oc.engine.adapter.time.SystemClock;
import de.augmentia.oc.engine.core.model.Principal;
import de.augmentia.oc.engine.core.model.SessionId;
import de.augmentia.oc.engine.core.wiring.StorageConfig;
import de.augmentia.oc.engine.core.wiring.StorageKind;
import de.augmentia.oc.engine.runtime.QocEngine;
import de.augmentia.oc.engine.runtime.callback.AgentResolver;
import de.augmentia.oc.engine.runtime.callback.EventPublisher;
import de.augmentia.oc.engine.runtime.callback.TracerProvider;
import de.augmentia.qoc.workflowrunner.model.WorkflowDefinition;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultNodeDispatcherParentSessionTest {

    @Test
    void agentNodeChildSessionEncodesTheWorkflowRunAsParent() {
        SystemClock clock = new SystemClock();
        QocEngine engine = QocEngine.create(
                MemoryStores.create(new RuntimeState(), clock, null),
                new StorageConfig(StorageKind.MEMORY), clock, false, null, engineConfig(),
                EventPublisher.noop(), AgentResolver.noop(), TracerProvider.noop());
        SessionId run = SessionId.create();
        DefaultNodeDispatcher dispatcher = new DefaultNodeDispatcher(
                engine, null, null, null, Principal.anonymous(), run.value());

        NodeResult result = dispatcher.dispatch(node(), definition(), new WorkflowContext(), "input");

        assertEquals(NodeResult.Status.COMPLETED, result.status());
        SessionId child = SessionId.parse(result.sessionId());
        assertTrue(child.isChild());
        assertEquals(run, child.parent().orElseThrow());
    }

    private static QocEngine.EngineConfig engineConfig() {
        return new QocEngine.EngineConfig(1, 1, 1, false, 1, 1, false, false, 1,
                false, 1, false, "none", "generic", 1, 1, 0, 1);
    }

    private static WorkflowDefinition definition() {
        WorkflowDefinition.Agent agent = new WorkflowDefinition.Agent(
                "a1", "Test Agent", "1.0.0", "", "model-x", 0.3, 4096, 1.0,
                List.of(), List.of(), List.of(), List.of(), false, true,
                "agent system prompt", null, "", List.of("*"), "all", List.of());
        return new WorkflowDefinition("wf1", "Test", "1.0.0", "draft",
                List.of(node()), List.of(), List.of(agent), "");
    }

    private static WorkflowDefinition.Node node() {
        return new WorkflowDefinition.Node(
                "n1", "agent", new WorkflowDefinition.Point(0, 0),
                new WorkflowDefinition.NodeData("Agent", "a1", false, "human", 3, "",
                        List.of(), "all", null, List.of(), "", "", null, "", "", Map.of(), null));
    }
}
