package de.augmentia.qoc.workflowrunner.runner;

import de.augmentia.oc.engine.adapter.StorageFactory;
import de.augmentia.oc.engine.adapter.fs.NioFileSystem;
import de.augmentia.oc.engine.adapter.memory.MemoryBudget;
import de.augmentia.oc.engine.adapter.memory.RuntimeState;
import de.augmentia.oc.engine.adapter.time.SystemClock;
import de.augmentia.oc.engine.core.model.EventDraft;
import de.augmentia.oc.engine.core.model.SessionError;
import de.augmentia.oc.engine.core.model.SessionEvent;
import de.augmentia.oc.engine.core.model.SessionId;
import de.augmentia.oc.engine.core.wiring.StorageConfig;
import de.augmentia.oc.engine.core.wiring.StorageKind;
import de.augmentia.oc.engine.core.wiring.Stores;
import de.augmentia.oc.engine.runtime.QocEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WorkflowRunJournalTest {

    private QocEngine engine;

    @BeforeEach
    void setUp() {
        StorageConfig config = new StorageConfig(StorageKind.MEMORY);
        RuntimeState memory = new RuntimeState();
        Stores stores = StorageFactory.create(config, memory, null, new SystemClock(),
                new NioFileSystem(Path.of(".")));
        engine = QocEngine.create(stores, config, new SystemClock(), false);
    }

    @Test
    void replaysCompletedNodesAndOutputs() {
        SessionId session = engine.createSession("workflow:demo", "tester", "workspace");
        append(session, new SessionEvent.WorkflowRunStarted(session, session.value(), "demo",
                "v1", "hash", "hello", Instant.EPOCH));
        append(session, new SessionEvent.WorkflowNodeStarted(session, session.value(), "start", 1, Instant.EPOCH));
        append(session, new SessionEvent.WorkflowNodeCompleted(session, session.value(), "start",
                "COMPLETED", "started", "started", "ses-child-start", Map.of("lastResult", "started"), Instant.EPOCH));
        append(session, new SessionEvent.WorkflowNodeStarted(session, session.value(), "end", 1, Instant.EPOCH));
        append(session, new SessionEvent.WorkflowNodeCompleted(session, session.value(), "end",
                "COMPLETED", "done", Map.of("answer", 42L), "ses-child-end", Map.of("lastResult", "done"), Instant.EPOCH));
        append(session, new SessionEvent.WorkflowRunFinished(session, session.value(), "COMPLETED", Instant.EPOCH));

        WorkflowRunSnapshot snapshot = new WorkflowRunJournal(engine).load(session).orElseThrow();
        assertEquals("demo", snapshot.workflowId());
        assertEquals("v1", snapshot.definitionVersion());
        assertEquals("COMPLETED", snapshot.status());
        assertEquals("end", snapshot.currentNodeId());
        assertNull(snapshot.activeNodeId());
        assertEquals(List.of("start", "end"), snapshot.completedNodes());
        assertEquals("done", snapshot.workflowState().get("lastResult"));
        assertEquals(Map.of("answer", 42L), snapshot.nodeOutputs().get("end"));
        assertEquals("ses-child-start", snapshot.nodeSessions().get("start"));
        assertEquals("ses-child-end", snapshot.nodeSessions().get("end"));
        assertFalse(snapshot.resumable());
    }

    @Test
    void unfinishedRunIsResumable() {
        SessionId session = engine.createSession("workflow:demo", "tester", "workspace");
        append(session, new SessionEvent.WorkflowRunStarted(session, session.value(), "demo",
                "v1", "hash", "hello", Instant.EPOCH));
        append(session, new SessionEvent.WorkflowNodeStarted(session, session.value(), "start", 1, Instant.EPOCH));
        append(session, new SessionEvent.WorkflowNodeCompleted(session, session.value(), "start",
                "COMPLETED", "started", "started", "ses-child-start", Map.of("lastResult", "started"), Instant.EPOCH));
        append(session, new SessionEvent.WorkflowNodeStarted(session, session.value(), "end", 2, Instant.EPOCH));

        WorkflowRunSnapshot snapshot = new WorkflowRunJournal(engine).load(session).orElseThrow();
        assertEquals("RUNNING", snapshot.status());
        assertEquals("start", snapshot.currentNodeId());
        assertEquals("end", snapshot.activeNodeId());
        assertTrue(snapshot.resumable());
        assertFalse(snapshot.active());
    }

    @Test
    void failedRunKeepsErrorAndLastCompletedNode() {
        SessionId session = engine.createSession("workflow:demo", "tester", "workspace");
        append(session, new SessionEvent.WorkflowRunStarted(session, session.value(), "demo",
                "v1", "hash", "hello", Instant.EPOCH));
        append(session, new SessionEvent.WorkflowNodeFailed(session, session.value(), "start",
                new SessionError.Unknown("node", "boom"), 1, Instant.EPOCH));
        append(session, new SessionEvent.WorkflowRunFailed(session, session.value(),
                new SessionError.Unknown("workflow", "boom"), Instant.EPOCH));

        WorkflowRunSnapshot snapshot = new WorkflowRunJournal(engine).load(session).orElseThrow();
        assertEquals("FAILED", snapshot.status());
        assertNull(snapshot.currentNodeId());
        assertEquals("start", snapshot.activeNodeId());
        assertEquals("hello", snapshot.workflowState().get("lastResult"));
        assertTrue(snapshot.error().contains("boom"));
        assertTrue(snapshot.resumable());
    }

    @Test
    void scanFindsWorkflowSessionsOnly() {
        SessionId workflow = engine.createSession("workflow:demo", "tester", "workspace");
        append(workflow, new SessionEvent.WorkflowRunStarted(workflow, workflow.value(), "demo",
                "v1", "hash", "hello", Instant.EPOCH));
        engine.createSession("build", "tester", "workspace");

        List<WorkflowRunSnapshot> runs = new WorkflowRunJournal(engine).scan(10);
        assertEquals(1, runs.size());
        assertEquals(workflow.value(), runs.get(0).runId());
    }

    @Test
    void invalidRunIdIsEmpty() {
        assertTrue(new WorkflowRunJournal(engine).load("not-a-session").isEmpty());
    }

    private void append(SessionId session, SessionEvent event) {
        engine.stores().journal().append(new EventDraft(session.value(), 1, event));
    }
}
