package de.augmentia.oc.engine.runtime;

import de.augmentia.oc.engine.adapter.memory.MemoryStores;
import de.augmentia.oc.engine.adapter.memory.RuntimeState;
import de.augmentia.oc.engine.core.model.AgentId;
import de.augmentia.oc.engine.core.model.Seq;
import de.augmentia.oc.engine.core.model.SessionEvent;
import de.augmentia.oc.engine.core.model.SessionId;
import de.augmentia.oc.engine.core.wiring.StorageConfig;
import de.augmentia.oc.engine.core.wiring.StorageKind;
import de.augmentia.oc.engine.core.wiring.Stores;
import de.augmentia.oc.engine.runtime.callback.AgentResolver;
import de.augmentia.oc.engine.runtime.callback.EventPublisher;
import de.augmentia.oc.engine.runtime.callback.TracerProvider;
import de.augmentia.oc.engine.testing.FixedClock;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class QocEngineChildRecoveryTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void orphanedChildIsNotResumedAsBuild() {
        FixedClock clock = new FixedClock(NOW);
        Stores stores = MemoryStores.create(new RuntimeState(), clock, null);
        QocEngine engine = engine(stores, clock, AgentResolver.noop());

        SessionId parent = SessionId.create();
        SessionId child = SessionId.create(parent);
        assertThat(stores.claims().acquire(child, clock.now()).isPresent()).isTrue();

        engine.recover();

        assertThat(stores.claims().open()).isEmpty();
        var orphaned = stores.journal().historyOfType(child.value(), "ChildSessionOrphaned", Seq.zero());
        assertThat(orphaned).hasSize(1);
        assertThat(((SessionEvent.ChildSessionOrphaned) orphaned.getFirst().payload()).parentId())
                .isEqualTo(parent);
        assertThat(stores.journal().historyOfType(child.value(), "ExecutionStarted", Seq.zero()))
                .isEmpty();
    }

    @Test
    void genericSessionIsResumedWithItsOwnAgent() {
        FixedClock clock = new FixedClock(NOW);
        Stores stores = MemoryStores.create(new RuntimeState(), clock, null);
        AtomicReference<AgentId> resolved = new AtomicReference<>();
        AgentResolver resolver = new AgentResolver() {
            @Override
            public String nameOf(AgentId agent) {
                resolved.set(agent);
                return "wf:recovered:1.0.0";
            }

            @Override
            public QocEngine.AgentConfig config(String name) {
                return null;
            }
        };
        QocEngine engine = engine(stores, clock, resolver);

        SessionId session = engine.createSession("build", "alice", "workspace");
        AgentId expected = engine.session(session).orElseThrow().agent();
        assertThat(stores.claims().acquire(session, clock.now()).isPresent()).isTrue();

        engine.recover();

        assertThat(resolved.get()).isEqualTo(expected);
        assertThat(stores.claims().open()).isEmpty();
        assertThat(stores.journal().historyOfType(session.value(), "SyntheticRaised", Seq.zero()))
                .hasSize(1);
    }

    @Test
    void createSessionWithParentEncodesTheParent() {
        FixedClock clock = new FixedClock(NOW);
        Stores stores = MemoryStores.create(new RuntimeState(), clock, null);
        QocEngine engine = engine(stores, clock, AgentResolver.noop());

        SessionId parent = SessionId.create();
        SessionId child = engine.createSession("build", "alice", "workspace", parent);

        assertThat(child.isChild()).isTrue();
        assertThat(child.parent().orElseThrow()).isEqualTo(parent);
        assertThat(engine.session(child).isPresent()).isTrue();
    }

    private static QocEngine engine(Stores stores, FixedClock clock, AgentResolver resolver) {
        return QocEngine.create(stores, new StorageConfig(StorageKind.SQLITE), clock, true,
                null, config(), EventPublisher.noop(), resolver, TracerProvider.noop());
    }

    private static QocEngine.EngineConfig config() {
        return new QocEngine.EngineConfig(1, 1, 1, false, 1, 1, false, false, 1,
                false, 1, false, "none", "generic", 1, 1, 0, 1);
    }
}
