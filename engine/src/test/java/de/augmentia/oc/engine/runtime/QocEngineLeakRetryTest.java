package de.augmentia.oc.engine.runtime;

import de.augmentia.oc.engine.adapter.memory.MemoryStores;
import de.augmentia.oc.engine.adapter.memory.RuntimeState;
import de.augmentia.oc.engine.core.contract.provider.ModelEvent;
import de.augmentia.oc.engine.core.contract.provider.ProviderAdapter;
import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.model.EngineResult;
import de.augmentia.oc.engine.core.model.FinishReason;
import de.augmentia.oc.engine.core.model.TokenCount;
import de.augmentia.oc.engine.core.wiring.StorageConfig;
import de.augmentia.oc.engine.core.wiring.StorageKind;
import de.augmentia.oc.engine.core.wiring.Stores;
import de.augmentia.oc.engine.runtime.callback.AgentResolver;
import de.augmentia.oc.engine.runtime.callback.EventPublisher;
import de.augmentia.oc.engine.runtime.callback.TracerProvider;
import de.augmentia.oc.engine.testing.FixedClock;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class QocEngineLeakRetryTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void leakedMarkupIsRetriedWithRepairHintAndNotPersisted() {
        List<String> systemPrompts = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        ProviderAdapter provider = request -> {
            systemPrompts.add(String.join("\n", request.system()));
            if (calls.getAndIncrement() == 0) {
                String leak = """
                        intro

                        <｜｜DSML｜｜ calls>
                        <｜｜DSML｜｜ invoke name="shell">
                        <｜｜DSML｜｜ parameter name="command" string="true">ls</｜｜DSML｜｜ parameter>
                        </｜｜DSML｜｜ invoke>
                        </｜｜DSML｜｜ calls>""";
                return Stream.of(
                        new ModelEvent.TextStarted("text-1"),
                        new ModelEvent.TextDelta("text-1", leak),
                        new ModelEvent.TextEnded("text-1", leak),
                        new ModelEvent.Finished(FinishReason.NORMAL, TokenCount.zero(), JsonValue.ofNull()));
            }
            return Stream.of(
                    new ModelEvent.TextStarted("text-1"),
                    new ModelEvent.TextDelta("text-1", "done"),
                    new ModelEvent.TextEnded("text-1", "done"),
                    new ModelEvent.Finished(FinishReason.NORMAL, TokenCount.zero(), JsonValue.ofNull()));
        };

        FixedClock clock = new FixedClock(NOW);
        Stores stores = MemoryStores.create(new RuntimeState(), clock, null);
        QocEngine engine = QocEngine.create(stores, new StorageConfig(StorageKind.MEMORY), clock, false,
                provider, config(), EventPublisher.noop(), AgentResolver.noop(), TracerProvider.noop());

        EngineResult result = engine.runPrompt("build", "hello");

        assertThat(result.failed()).isFalse();
        assertThat(result.text()).contains("done");
        assertThat(result.text()).doesNotContain("DSML");
        assertThat(calls).hasValue(2);
        assertThat(systemPrompts).hasSize(2);
        assertThat(systemPrompts.get(1)).contains("previous attempt returned provider-native tool-call markup");
    }

    @Test
    void repeatedLeakStopsAfterOneRepairAttempt() {
        AtomicInteger calls = new AtomicInteger();
        String leak = """
                <|DSML| calls>
                <|DSML| invoke name="shell">
                <|DSML| parameter name="command" string="true">ls</|DSML| parameter>
                </|DSML| invoke>
                </|DSML| calls>""";
        ProviderAdapter provider = request -> {
            calls.incrementAndGet();
            return Stream.of(
                    new ModelEvent.TextEnded("text-1", leak),
                    new ModelEvent.Finished(FinishReason.NORMAL, TokenCount.zero(), JsonValue.ofNull()));
        };

        FixedClock clock = new FixedClock(NOW);
        Stores stores = MemoryStores.create(new RuntimeState(), clock, null);
        QocEngine engine = QocEngine.create(stores, new StorageConfig(StorageKind.MEMORY), clock, false,
                provider, config(), EventPublisher.noop(), AgentResolver.noop(), TracerProvider.noop());

        EngineResult result = engine.runPrompt("build", "hello");

        assertThat(result.failed()).isTrue();
        assertThat(result.retryable()).isFalse();
        assertThat(calls).hasValue(2);
    }

    private static QocEngine.EngineConfig config() {
        return new QocEngine.EngineConfig(1, 1, 1, false, 1, 1, false, false, 1,
                false, 1, false, "none", "generic", 1, 1, 0, 1);
    }
}
