package de.augmentia.oc.engine.runtime.tool.runtime;

import de.augmentia.oc.engine.core.contract.permission.PermissionDenied;
import de.augmentia.oc.engine.core.contract.permission.PermissionGate;
import de.augmentia.oc.engine.core.contract.tool.Hooks;
import de.augmentia.oc.engine.core.contract.tool.ToolCall;
import de.augmentia.oc.engine.core.contract.tool.ToolContext;
import de.augmentia.oc.engine.core.contract.tool.ToolDefinition;
import de.augmentia.oc.engine.core.contract.tool.ToolFailure;
import de.augmentia.oc.engine.core.contract.tool.ToolOutcome;
import de.augmentia.oc.engine.core.contract.tool.ToolResult;
import de.augmentia.oc.engine.core.contract.tool.ToolSnapshot;
import de.augmentia.oc.engine.core.error.PausedException;
import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.json.Schema;
import de.augmentia.oc.engine.core.model.AgentId;
import de.augmentia.oc.engine.core.model.CallId;
import de.augmentia.oc.engine.core.model.IdempotencyKey;
import de.augmentia.oc.engine.core.model.MessageId;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.SessionId;
import de.augmentia.oc.engine.core.model.ToolContent;
import de.augmentia.oc.engine.core.model.ToolName;
import de.augmentia.oc.engine.core.port.store.ToolResultCache;
import de.augmentia.oc.engine.testing.InMemoryToolOutput;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Covers cache, timeout and exception branches of the tool runtime. */
class ToolRuntimeCoverageTest {

    private static final ToolName NAME = new ToolName("echo");

    @Test
    void constructorRejectsEveryMissingDependency() {
        ToolSnapshot snapshot = snapshot(definition((input, context) -> new ToolOutcome.Ok(ToolResult.of("ok"))));
        ToolAuthorizer authorizer = ToolAuthorizer.defaultAuthorizer();
        PermissionGate gate = request -> {};
        Hooks hooks = noopHooks();
        InMemoryToolOutput output = new InMemoryToolOutput();

        assertThatThrownBy(() -> new ToolRuntime(null, authorizer, gate, hooks, output, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ToolRuntime(snapshot, null, gate, hooks, output, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ToolRuntime(snapshot, authorizer, null, hooks, output, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ToolRuntime(snapshot, authorizer, gate, null, output, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ToolRuntime(snapshot, authorizer, gate, hooks, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void executeRejectsNullCallOrContext() {
        ToolRuntime runtime = runtime(definition((input, context) -> new ToolOutcome.Ok(ToolResult.of("ok"))),
                request -> {}, noopHooks(), null, 30);

        assertThatThrownBy(() -> runtime.execute(null, context()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> runtime.execute(call(), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cacheHitReturnsTheStoredOutcomeWithoutExecuting() {
        AtomicBoolean executed = new AtomicBoolean();
        Cache cache = new Cache();
        ToolContext toolContext = context();
        cache.values.put(toolContext.idempotencyKey(), ToolOutcomeJson.encode(new ToolOutcome.Ok(
                new ToolResult(Option.none(), List.of(new ToolContent.TextItem("cached")), Map.of()))));
        ToolRuntime runtime = runtime(definition((input, context) -> {
            executed.set(true);
            return new ToolOutcome.Ok(ToolResult.of("fresh"));
        }), request -> {}, noopHooks(), cache, 30);

        ToolOutcome.Ok outcome = (ToolOutcome.Ok) runtime.execute(call(), toolContext);

        assertThat(((ToolContent.TextItem) outcome.result().content().getFirst()).text()).isEqualTo("cached");
        assertThat(executed).isFalse();
    }

    @Test
    void undecodableCacheEntryFallsThroughToExecution() {
        Cache cache = new Cache();
        ToolContext toolContext = context();
        cache.values.put(toolContext.idempotencyKey(), JsonValue.of(5));
        ToolRuntime runtime = runtime(definition((input, context) -> new ToolOutcome.Ok(ToolResult.of("fresh"))),
                request -> {}, noopHooks(), cache, 30);

        assertThat(runtime.execute(call(), toolContext)).isInstanceOf(ToolOutcome.Ok.class);
    }

    @Test
    void successfulOutcomeIsStoredInTheCache() {
        Cache cache = new Cache();
        ToolContext toolContext = context();
        ToolRuntime runtime = runtime(definition((input, context) -> new ToolOutcome.Ok(ToolResult.of("ok"))),
                request -> {}, noopHooks(), cache, 30);

        runtime.execute(call(), toolContext);

        assertThat(cache.values).containsKey(toolContext.idempotencyKey());
    }

    @Test
    void cacheStoreFailureDoesNotFailTheCall() {
        Cache cache = new Cache();
        cache.failOnStore = true;
        ToolRuntime runtime = runtime(definition((input, context) -> new ToolOutcome.Ok(ToolResult.of("ok"))),
                request -> {}, noopHooks(), cache, 30);

        assertThat(runtime.execute(call(), context())).isInstanceOf(ToolOutcome.Ok.class);
    }

    @Test
    void permissionDenialIsCachedAsFailure() {
        Cache cache = new Cache();
        ToolRuntime runtime = runtime(definition((input, context) -> new ToolOutcome.Ok(ToolResult.of("ok"))),
                request -> {
                    throw new PermissionDenied(request, "no");
                }, noopHooks(), cache, 30);

        ToolOutcome outcome = runtime.execute(call(), context());

        assertThat(outcome).isInstanceOf(ToolOutcome.Failed.class);
        assertThat(cache.values).hasSize(1);
    }

    @Test
    void pausedPermissionGateProducesPausedOutcome() {
        ToolRuntime runtime = runtime(definition((input, context) -> new ToolOutcome.Ok(ToolResult.of("ok"))),
                request -> {
                    throw new PausedException("ap-1", "url");
                }, noopHooks(), null, 30);

        ToolOutcome.Paused paused = (ToolOutcome.Paused) runtime.execute(call(), context());

        assertThat(paused.approvalId()).isEqualTo("ap-1");
        assertThat(paused.approvalUrl()).isEqualTo("url");
    }

    @Test
    void preHookReturningNullIsAToolFailure() {
        Hooks hooks = new Hooks() {
            @Override
            public ToolCall beforeToolCall(ToolContext context, ToolCall call) {
                return null;
            }

            @Override
            public ToolResult afterToolCall(ToolContext context, ToolResult result) {
                return result;
            }
        };
        ToolRuntime runtime = runtime(definition((input, context) -> new ToolOutcome.Ok(ToolResult.of("ok"))),
                request -> {}, hooks, null, 30);

        ToolOutcome.Failed failed = (ToolOutcome.Failed) runtime.execute(call(), context());

        assertThat(failed.error().message()).contains("pre-hook returned no call");
    }

    @Test
    void emptyOutcomePassesThroughUnchanged() {
        ToolRuntime runtime = runtime(definition((input, context) -> new ToolOutcome.Empty("none")),
                request -> {}, noopHooks(), null, 30);

        ToolOutcome outcome = runtime.execute(call(), context());

        assertThat(outcome).isInstanceOf(ToolOutcome.Empty.class);
        assertThat(((ToolOutcome.Empty) outcome).message()).isEqualTo("none");
    }

    @Test
    void unexpectedExecutorExceptionBecomesToolError() {
        ToolRuntime runtime = runtime(definition((input, context) -> {
            throw new IllegalStateException("exploded");
        }), request -> {}, noopHooks(), null, 30);

        ToolOutcome.Failed failed = (ToolOutcome.Failed) runtime.execute(call(), context());

        assertThat(failed.error().message()).isEqualTo("tool error: exploded");
    }

    @Test
    void toolFailureIsCached() {
        Cache cache = new Cache();
        ToolDefinition strict = new ToolDefinition(NAME, "echo",
                Schema.obj(Map.of("text", Schema.str())), Option.none(),
                (input, context) -> new ToolOutcome.Ok(ToolResult.of("ok")), null);
        ToolRuntime runtime = runtime(strict, request -> {}, noopHooks(), cache, 30);

        // Empty input fails schema decoding and raises ToolFailure inside the runtime.
        ToolOutcome outcome = runtime.execute(call(), context());

        assertThat(outcome).isInstanceOf(ToolOutcome.Failed.class);
        assertThat(cache.values).hasSize(1);
    }

    @Test
    void timeoutCancelsTheCallAndReportsAFailure() {
        ToolRuntime runtime = runtime(definition((input, context) -> {
            try {
                Thread.sleep(3_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new ToolOutcome.Ok(ToolResult.of("late"));
        }), request -> {}, noopHooks(), null, 1);

        ToolOutcome.Failed failed = (ToolOutcome.Failed) runtime.execute(call(), context());

        assertThat(failed.error().message()).contains("step timeout");
    }

    @Test
    void longRunningToolsBypassTheTimeout() {
        ToolDefinition subagent = new ToolDefinition(new ToolName("subagent"), "spawn",
                Schema.obj(Map.of()), Option.none(),
                (input, context) -> new ToolOutcome.Ok(ToolResult.of("done")), null);
        ToolRuntime runtime = new ToolRuntime(new ToolSnapshot(Map.of(subagent.name(), subagent)),
                ToolAuthorizer.defaultAuthorizer(), request -> {}, noopHooks(),
                new InMemoryToolOutput(), null, 1);

        ToolOutcome outcome = runtime.execute(new ToolCall(CallId.create(),
                new ToolName("subagent"), JsonValue.of(Map.of())), context());

        assertThat(outcome).isInstanceOf(ToolOutcome.Ok.class);
    }

    private static ToolRuntime runtime(ToolDefinition definition, PermissionGate gate, Hooks hooks,
                                       ToolResultCache cache, long timeoutSeconds) {
        return new ToolRuntime(snapshot(definition), ToolAuthorizer.defaultAuthorizer(), gate, hooks,
                new InMemoryToolOutput(), cache, timeoutSeconds);
    }

    private static ToolSnapshot snapshot(ToolDefinition definition) {
        return new ToolSnapshot(Map.of(definition.name(), definition));
    }

    private static ToolDefinition definition(
            de.augmentia.oc.engine.core.contract.tool.ToolExecutor executor) {
        return new ToolDefinition(NAME, "echo", Schema.obj(Map.of()), Option.none(), executor, null);
    }

    private static Hooks noopHooks() {
        return new Hooks() {
            @Override
            public ToolCall beforeToolCall(ToolContext context, ToolCall call) {
                return call;
            }

            @Override
            public ToolResult afterToolCall(ToolContext context, ToolResult result) {
                return result;
            }
        };
    }

    private static ToolCall call() {
        return new ToolCall(CallId.create(), NAME, JsonValue.of(Map.of()));
    }

    private static ToolContext context() {
        return new ToolContext(SessionId.create(), AgentId.create(), MessageId.create(), CallId.create());
    }

    private static final class Cache implements ToolResultCache {
        private final Map<IdempotencyKey, JsonValue> values = new HashMap<>();
        private boolean failOnStore;

        @Override
        public Optional<JsonValue> find(IdempotencyKey key) {
            return Optional.ofNullable(values.get(key));
        }

        @Override
        public void store(IdempotencyKey key, String sessionId, String toolName, JsonValue outcome) {
            if (failOnStore) throw new IllegalStateException("cache down");
            values.put(key, outcome);
        }

        @Override
        public void delete(IdempotencyKey key) {
            values.remove(key);
        }

        @Override
        public void deleteBySession(String sessionId) {
            values.clear();
        }

        @Override
        public int deleteCreatedBefore(Instant cutoff) {
            return 0;
        }
    }
}
