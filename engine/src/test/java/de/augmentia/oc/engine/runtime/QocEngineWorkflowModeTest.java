package de.augmentia.oc.engine.runtime;

import de.augmentia.oc.engine.adapter.memory.MemoryStores;
import de.augmentia.oc.engine.adapter.memory.RuntimeState;
import de.augmentia.oc.engine.core.model.Principal;
import de.augmentia.oc.engine.core.model.SessionId;
import de.augmentia.oc.engine.core.wiring.StorageConfig;
import de.augmentia.oc.engine.core.wiring.StorageKind;
import de.augmentia.oc.engine.core.wiring.Stores;
import de.augmentia.oc.engine.testing.FixedClock;
import de.augmentia.oc.engine.runtime.workflow.WorkflowStepProvider;
import de.augmentia.oc.engine.runtime.step.StepResult;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class QocEngineWorkflowModeTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private static Stores stores(FixedClock clock) {
        return MemoryStores.create(new RuntimeState(), clock, null);
    }

    private static QocEngine engineWithProvider(WorkflowStepProvider provider) {
        FixedClock clock = new FixedClock(NOW);
        return QocEngine.create(stores(clock), new StorageConfig(StorageKind.MEMORY), clock,
                false, request -> { throw new AssertionError("LLM must not be called in workflow mode"); },
                QocEngine.EngineConfig.defaults(),
                de.augmentia.oc.engine.runtime.callback.EventPublisher.noop(),
                de.augmentia.oc.engine.runtime.callback.AgentResolver.noop(),
                de.augmentia.oc.engine.runtime.callback.TracerProvider.noop());
    }

    @Test
    void workflowModeRunsProviderUntilWorkflowDoneWithoutLlm() {
        AtomicInteger calls = new AtomicInteger();
        WorkflowStepProvider provider = (stepNo, ctx) -> {
            calls.incrementAndGet();
            if (stepNo == 1) {
                return new StepResult.Finished(
                        de.augmentia.oc.engine.core.model.FinishReason.NORMAL,
                        de.augmentia.oc.engine.core.model.TokenCount.of(1, 2),
                        List.of(new de.augmentia.oc.engine.core.contract.provider.ModelEvent.TextDelta("n1", "node-1")),
                        List.of());
            }
            return new StepResult.WorkflowDone();
        };

        QocEngine engine = engineWithProvider(provider);
        SessionId id = engine.createSession("workflow:test", "alice", "workspace");
        var result = engine.runPrompt(id, "workflow:test", "input", null, true, provider, new Object(),
                Principal.anonymous());

        assertThat(result.failed()).isFalse();
        assertThat(result.text()).contains("node-1");
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void workflowModePropagatesPaused() {
        WorkflowStepProvider provider = (stepNo, ctx) ->
                new StepResult.Paused("approval-1", "/approve/approval-1");

        QocEngine engine = engineWithProvider(provider);
        SessionId id = engine.createSession("workflow:test", "alice", "workspace");
        var result = engine.runPrompt(id, "workflow:test", "input", null, true, provider, new Object(),
                Principal.anonymous());

        assertThat(result.paused()).isTrue();
        assertThat(result.approvalId()).isEqualTo("approval-1");
    }

    @Test
    void workflowModeFailsOnNeedsCompaction() {
        WorkflowStepProvider provider = (stepNo, ctx) -> new StepResult.NeedsCompaction();

        QocEngine engine = engineWithProvider(provider);
        SessionId id = engine.createSession("workflow:test", "alice", "workspace");
        var result = engine.runPrompt(id, "workflow:test", "input", null, true, provider, new Object(),
                Principal.anonymous());

        assertThat(result.failed()).isTrue();
        assertThat(result.errorMessage()).contains("compaction");
    }

    @Test
    void workflowModeFailsWhenMaxStepsExceeded() {
        WorkflowStepProvider provider = (stepNo, ctx) -> new StepResult.Finished(
                de.augmentia.oc.engine.core.model.FinishReason.NORMAL,
                de.augmentia.oc.engine.core.model.TokenCount.zero(),
                List.of(new de.augmentia.oc.engine.core.contract.provider.ModelEvent.TextDelta("n", "x")),
                List.of());

        QocEngine engine = engineWithProvider(provider);
        engine.setWorkflowMaxSteps(2);
        SessionId id = engine.createSession("workflow:test", "alice", "workspace");
        var result = engine.runPrompt(id, "workflow:test", "input", null, true, provider, new Object(),
                Principal.anonymous());

        assertThat(result.failed()).isTrue();
        assertThat(result.errorMessage()).contains("max steps");
    }

    @Test
    void workflowModeRejectsProviderWithoutContext() {
        QocEngine engine = engineWithProvider((stepNo, ctx) -> new StepResult.WorkflowDone());
        SessionId id = engine.createSession("workflow:test", "alice", "workspace");
        var result = engine.runPrompt(id, "workflow:test", "input", null, true,
                (WorkflowStepProvider) (stepNo, ctx) -> new StepResult.WorkflowDone(), null,
                Principal.anonymous());

        assertThat(result.failed()).isTrue();
        assertThat(result.errorMessage()).contains("provided together");
    }
}
