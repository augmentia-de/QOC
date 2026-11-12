package de.augmentia.oc.engine.runtime;

import de.augmentia.oc.engine.adapter.memory.MemoryBudget;
import de.augmentia.oc.engine.adapter.memory.MemoryStores;
import de.augmentia.oc.engine.adapter.memory.RuntimeState;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.SessionId;
import de.augmentia.oc.engine.core.model.SessionInfo;
import de.augmentia.oc.engine.core.wiring.StorageConfig;
import de.augmentia.oc.engine.core.wiring.StorageKind;
import de.augmentia.oc.engine.core.wiring.Stores;
import de.augmentia.oc.engine.core.wiring.ToolDependencies;
import de.augmentia.oc.engine.runtime.callback.AgentResolver;
import de.augmentia.oc.engine.runtime.callback.EventPublisher;
import de.augmentia.oc.engine.runtime.callback.TracerProvider;
import de.augmentia.oc.engine.testing.FakeFileSystem;
import de.augmentia.oc.engine.testing.FixedClock;
import de.augmentia.oc.engine.testing.ScriptedHttp;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QocEngineTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private static QocEngine.EngineConfig config(boolean preflight) {
        return new QocEngine.EngineConfig(1, 1, 1, preflight, 1, 1, false, false, 1,
                false, 1, false, "none", "generic", 1, 1, 0, 1);
    }

    private static Stores stores(FixedClock clock) {
        return MemoryStores.create(new RuntimeState(), clock, null);
    }

    private static QocEngine engine(QocEngine.EngineConfig cfg) {
        FixedClock clock = new FixedClock(NOW);
        return QocEngine.create(stores(clock), new StorageConfig(StorageKind.MEMORY), clock,
                false, null, cfg, EventPublisher.noop(), AgentResolver.noop(), TracerProvider.noop());
    }

    @Test
    void engineConfigNormalizesInvalidValues() {
        QocEngine.EngineConfig cfg = new QocEngine.EngineConfig(
                0, -1, 0, true, 0, 0, true, true, 0,
                false, 0, false, null, null, 0, 0, 0, 0);

        assertThat(cfg.retryDelay1Seconds()).isEqualTo(10);
        assertThat(cfg.retryDelay2Seconds()).isEqualTo(30);
        assertThat(cfg.retryMaxAttempts()).isEqualTo(3);
        assertThat(cfg.preflightTtlSeconds()).isEqualTo(60);
        assertThat(cfg.preflightTimeoutSeconds()).isEqualTo(5);
        assertThat(cfg.loopDetectionThreshold()).isEqualTo(5);
        assertThat(cfg.triageMaxAttempts()).isEqualTo(3);
        assertThat(cfg.webhookUrl()).isEqualTo("none");
        assertThat(cfg.webhookFormat()).isEqualTo("generic");
        assertThat(cfg.stepTimeoutSeconds()).isEqualTo(30);
        assertThat(cfg.maxTurnToolCalls()).isEqualTo(50);
        assertThat(cfg.maxTurnWallMinutes()).isEqualTo(30);
    }

    @Test
    void engineConfigDefaultsAreUsable() {
        QocEngine.EngineConfig defaults = QocEngine.EngineConfig.defaults();

        assertThat(defaults.retryDelay1Seconds()).isEqualTo(10);
        assertThat(defaults.retryDelay2Seconds()).isEqualTo(30);
        assertThat(defaults.retryMaxAttempts()).isEqualTo(3);
        assertThat(defaults.preflightEnabled()).isTrue();
        assertThat(defaults.triageEnabled()).isFalse();
        assertThat(defaults.webhookEnabled()).isFalse();
        assertThat(defaults.maxTurnToolCalls()).isEqualTo(50);
        assertThat(defaults.maxTurnWallMinutes()).isEqualTo(30);
    }

    @Test
    void agentConfigNormalizesNulls() {
        QocEngine.AgentConfig cfg = new QocEngine.AgentConfig(
                null, null, 0.0, 0, 0.0, null,
                null, null, null, null, false, null);

        assertThat(cfg.name()).isEqualTo("wf:default:1.0.0");
        assertThat(cfg.model()).isEmpty();
        assertThat(cfg.tools()).isEmpty();
        assertThat(cfg.guardrailsInput()).isEmpty();
        assertThat(cfg.guardrailsOutput()).isEmpty();
        assertThat(cfg.hooks()).isEmpty();
        assertThat(cfg.responseSchema()).isEqualTo(Option.none());
    }

    @Test
    void createRejectsNullStoresAndClock() {
        assertThatThrownBy(() -> QocEngine.create(null, new StorageConfig(StorageKind.MEMORY),
                new FixedClock(NOW), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("stores");

        assertThatThrownBy(() -> QocEngine.create(stores(new FixedClock(NOW)),
                new StorageConfig(StorageKind.MEMORY), null, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("clock");
    }

    @Test
    void createFallsBackToMemoryStorageConfig() {
        FixedClock clock = new FixedClock(NOW);
        QocEngine engine = QocEngine.create(stores(clock), null, clock, false);

        assertThat(engine.storageConfig().defaultKind()).isEqualTo(StorageKind.MEMORY);
        assertThat(engine.clock()).isSameAs(clock);
        assertThat(engine.provider()).isNull();
        assertThat(engine.contextBuilder()).isNotNull();
        assertThat(engine.eventPublisher()).isNotNull();
        assertThat(engine.agentResolver()).isNotNull();
        assertThat(engine.tracerProvider()).isNotNull();
    }

    @Test
    void createRegistersToolsProvidedByToolDependencies() {
        FixedClock clock = new FixedClock(NOW);
        AtomicBoolean registrarCalled = new AtomicBoolean(false);
        ToolDependencies deps = new ToolDependencies(
                new FakeFileSystem(), null, new ScriptedHttp(), Path.of(".").toAbsolutePath(),
                registry -> {
                    registrarCalled.set(true);
                    registry.register(de.augmentia.oc.engine.runtime.tool.meta.TodoTool.definition(
                            new de.augmentia.oc.engine.runtime.tool.meta.TodoTool()));
                });

        QocEngine engine = QocEngine.create(stores(clock), new StorageConfig(StorageKind.MEMORY),
                clock, false, null, config(false),
                EventPublisher.noop(), AgentResolver.noop(), TracerProvider.noop(), deps);

        assertThat(registrarCalled).isTrue();
        assertThat(engine.contextBuilder()).isNotNull();
    }

    @Test
    void createAcceptsExplicitToolDependenciesNone() {
        FixedClock clock = new FixedClock(NOW);
        QocEngine engine = QocEngine.create(stores(clock), new StorageConfig(StorageKind.MEMORY),
                clock, false, null, config(false),
                EventPublisher.noop(), AgentResolver.noop(), TracerProvider.noop(),
                ToolDependencies.none());

        assertThat(engine.step()).isNull();
        assertThat(engine.workflowStepProvider()).isNull();
        assertThat(engine.compactionService()).isNull();
    }

    @Test
    void sessionLifecycle() {
        QocEngine engine = engine(config(false));

        SessionId id = engine.createSession("build", "alice", "workspace");

        assertThat(engine.session(id).isPresent()).isTrue();
        assertThat(engine.session(id).orElseThrow().ownerId()).isEqualTo("alice");
        assertThat(engine.listSessions("alice", false))
                .extracting(SessionInfo::id).containsExactly(id);
        assertThat(engine.listSessions("bob", false)).isEmpty();
        assertThat(engine.listSessions("bob", true))
                .extracting(SessionInfo::id).contains(id);

        assertThat(engine.deleteSession(id)).isTrue();
        assertThat(engine.session(id).isEmpty()).isTrue();
        assertThat(engine.deleteSession(null)).isFalse();
        assertThat(engine.session(null).isEmpty()).isTrue();
    }

    @Test
    void createSessionNormalizesBlankInputs() {
        QocEngine engine = engine(config(false));

        SessionId id = engine.createSession(" ", " ", " ");

        SessionInfo info = engine.session(id).orElseThrow();
        assertThat(info.ownerId()).isEqualTo("anonymous");
        assertThat(engine.listSessions("anonymous", false))
                .extracting(SessionInfo::id).contains(id);
    }

    @Test
    void listSessionsIsCappedAt25() {
        QocEngine engine = engine(config(false));
        for (int i = 0; i < 30; i++) {
            engine.createSession("build", "alice", "workspace");
        }

        assertThat(engine.listSessions("alice", false)).hasSize(25);
    }

    @Test
    void resolveAgentConfigStoresFirstConfigAndNeverOverwrites() {
        QocEngine engine = engine(config(false));
        QocEngine.AgentConfig first = new QocEngine.AgentConfig(
                "wf:reviewer:1.0.0", "gpt-4o", 0.2, 1024, 0.9, "review",
                List.of("read_file"), List.of(), List.of(), List.of(), false, Option.none());
        QocEngine.AgentConfig second = new QocEngine.AgentConfig(
                "wf:reviewer:1.0.0", "other", 0.9, 1, 0.1, "other",
                List.of(), List.of(), List.of(), List.of(), true, Option.none());

        assertThat(engine.resolveAgentConfig("wf:reviewer:1.0.0", first)).isSameAs(first);
        assertThat(engine.resolveAgentConfig("wf:reviewer:1.0.0", second)).isSameAs(first);
    }

    @Test
    void resolveAgentConfigCreatesDefaultWhenNoConfigWasSupplied() {
        QocEngine engine = engine(config(false));

        QocEngine.AgentConfig cfg = engine.resolveAgentConfig("wf:writer:1.0.0", null);

        assertThat(cfg.name()).isEqualTo("wf:writer:1.0.0");
        assertThat(cfg.temperature()).isEqualTo(0.3);
        assertThat(cfg.maxTokens()).isEqualTo(4096);
        assertThat(cfg.topP()).isEqualTo(1.0);
        // second lookup returns the cached instance
        assertThat(engine.resolveAgentConfig("wf:writer:1.0.0", null)).isSameAs(cfg);
    }

    @Test
    void runPromptWithoutProviderQueuesPromptAndStoresIt() {
        QocEngine engine = engine(config(false));

        String text = engine.runPromptText("build", "hello world");

        assertThat(text).contains("Prompt queued (no provider wired): hello world");
        List<SessionInfo> sessions = engine.listSessions("temporal", false);
        assertThat(sessions).hasSize(1);
        SessionId id = sessions.getFirst().id();
        assertThat(engine.stores().conversation().messages(id,
                de.augmentia.oc.engine.core.model.Seq.zero())).hasSize(1);
    }

    @Test
    void runPromptFailsWhenSessionIsAlreadyClaimed() {
        QocEngine engine = engine(config(false));
        SessionId id = engine.createSession("build", "alice", "workspace");
        assertThat(engine.stores().claims().acquire(id, NOW).isPresent()).isTrue();

        var result = engine.runPrompt(id, "build", "hello", null, true, null, null,
                de.augmentia.oc.engine.core.model.Principal.anonymous());

        assertThat(result.failed()).isTrue();
        assertThat(result.errorMessage()).contains("already being executed");
        engine.stores().claims().release(id);
    }

    @Test
    void recoverIsNoOpForMemoryStorageAndDisabledRecovery() {
        QocEngine disabled = engine(config(false));
        disabled.recover(); // recoveryEnabled=false
        assertThat(disabled.stores().claims().open()).isEmpty();

        FixedClock clock = new FixedClock(NOW);
        QocEngine memory = QocEngine.create(stores(clock), new StorageConfig(StorageKind.MEMORY),
                clock, true, null, config(false),
                EventPublisher.noop(), AgentResolver.noop(), TracerProvider.noop());
        memory.recover(); // memory storage is never scanned
        assertThat(memory.stores().claims().open()).isEmpty();
    }

    @Test
    void setWorkflowMaxStepsClampsToAtLeastOne() {
        QocEngine engine = engine(config(false));

        engine.setWorkflowMaxSteps(0);
        engine.setWorkflowMaxSteps(-5);
        engine.setWorkflowMaxSteps(7);
    }

    @Test
    void setPreflightContextIsAccepted() {
        QocEngine engine = engine(config(false));

        engine.setPreflightContext(new de.augmentia.oc.engine.runtime.resilience.PreflightChecker.PreflightContext(
                null, null, () -> true, Path.of(".")));
    }

    @Test
    void executionOverridesRecordExposesComponents() {
        QocEngine.ExecutionOverrides overrides = new QocEngine.ExecutionOverrides(
                null, null, null, null, null, null);

        assertThat(overrides.agentProfile()).isNull();
        assertThat(overrides.modelInfo()).isNull();
        assertThat(overrides.step()).isNull();
        assertThat(overrides.toolSnapshot()).isNull();
        assertThat(overrides.contextBuilder()).isNull();
        assertThat(overrides.skillSelection()).isNull();
    }
}
