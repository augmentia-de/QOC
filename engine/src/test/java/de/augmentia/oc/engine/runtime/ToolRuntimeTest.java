package de.augmentia.oc.engine.runtime;

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
import de.augmentia.oc.engine.core.error.Defect;
import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.json.Schema;
import de.augmentia.oc.engine.core.model.AgentId;
import de.augmentia.oc.engine.core.model.CallId;
import de.augmentia.oc.engine.core.model.MessageId;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.SessionId;
import de.augmentia.oc.engine.core.model.ToolContent;
import de.augmentia.oc.engine.core.model.ToolName;
import de.augmentia.oc.engine.runtime.tool.runtime.ToolAuthorizer;
import de.augmentia.oc.engine.runtime.tool.runtime.ToolRuntime;
import de.augmentia.oc.engine.testing.InMemoryToolOutput;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolRuntimeTest {

    private static final ToolName NAME = new ToolName("echo");

    @Test
    void happyPathDecodesChecksExecutesAndNormalizes() {
        AtomicReference<JsonValue> seen = new AtomicReference<>();
        AtomicReference<String> action = new AtomicReference<>();
        AtomicReference<String> called = new AtomicReference<>("");

        ToolDefinition definition = new ToolDefinition(NAME, "echo", inputSchema(), Option.of(Schema.str()),
                (input, context) -> {
                    seen.set(input);
                    return new ToolOutcome.Ok(new ToolResult(Option.some(JsonValue.of("out")),
                            List.of(new ToolContent.TextItem("done")), Map.of()));
                }, null);
        PermissionGate gate = request -> action.set(request.action());

        ToolOutcome outcome = runtime(definition, gate, new Hooks() {
            @Override
            public ToolCall beforeToolCall(ToolContext context, ToolCall call) {
                called.set("before");
                return call;
            }

            @Override
            public ToolResult afterToolCall(ToolContext context, ToolResult result) {
                called.set(called.get() + "/after");
                return result;
            }
        }).execute(call(Map.of("text", JsonValue.of("hi"))), context());

        assertThat(outcome).isInstanceOf(ToolOutcome.Ok.class);
        assertThat(seen.get()).isEqualTo(JsonValue.of(Map.of("text", JsonValue.of("hi"))));
        assertThat(action.get()).isEqualTo("echo");
        assertThat(called.get()).isEqualTo("before/after");
    }

    @Test
    void invalidInputIsNotExecuted() {
        AtomicBoolean executed = new AtomicBoolean();
        ToolDefinition definition = definition((input, context) -> {
            executed.set(true);
            return new ToolOutcome.Ok(ToolResult.of("ok"));
        }, inputSchema());

        ToolOutcome outcome = runtime(definition, noopGate(), noopHooks())
                .execute(call(Map.of("wrong", JsonValue.of("x"))), context());

        assertThat(outcome).isInstanceOf(ToolOutcome.Failed.class);
        assertThat(executed).isFalse();
    }

    @Test
    void permissionDenialPropagatesAndPreventsExecution() {
        AtomicBoolean executed = new AtomicBoolean();
        ToolDefinition definition = definition((input, context) -> {
            executed.set(true);
            return new ToolOutcome.Ok(ToolResult.of("ok"));
        }, inputSchema());

        PermissionGate denying = request -> {
            throw new PermissionDenied(request, "no");
        };

        ToolOutcome outcome = runtime(definition, denying, noopHooks())
                .execute(call(Map.of("text", JsonValue.of("x"))), context());

        assertThat(outcome).isInstanceOf(ToolOutcome.Failed.class);
        assertThat(((ToolOutcome.Failed) outcome).error().message()).isEqualTo("no");
        assertThat(executed).isFalse();
    }

    @Test
    void toolFailureBecomesAFailedOutcome() {
        ToolDefinition definition = definition((input, context) -> {
            throw new ToolFailure("broken");
        }, inputSchema());

        ToolOutcome outcome = runtime(definition, noopGate(), noopHooks())
                .execute(call(Map.of("text", JsonValue.of("x"))), context());

        assertThat(outcome).isInstanceOf(ToolOutcome.Failed.class);
        assertThat(((ToolOutcome.Failed) outcome).error().message()).isEqualTo("broken");
    }

    @Test
    void defectIsNotConvertedIntoAnError() {
        ToolDefinition definition = definition((input, context) -> {
            throw new Defect("bug");
        }, inputSchema());

        assertThatThrownBy(() -> runtime(definition, noopGate(), noopHooks())
                .execute(call(Map.of("text", JsonValue.of("x"))), context()))
                .isInstanceOf(Defect.class);
    }

    @Test
    void outputSchemaMismatchIsAFailedOutcome() {
        ToolDefinition definition = new ToolDefinition(NAME, "echo", inputSchema(), Option.of(Schema.str()),
                (input, context) -> new ToolOutcome.Ok(new ToolResult(Option.some(JsonValue.of(42L)),
                        List.of(new ToolContent.TextItem("x")), Map.of())), null);

        ToolOutcome outcome = runtime(definition, noopGate(), noopHooks())
                .execute(call(Map.of("text", JsonValue.of("x"))), context());

        assertThat(outcome).isInstanceOf(ToolOutcome.Failed.class);
    }

    @Test
    void missingSnapshotEntryIsAFailure() {
        ToolDefinition definition = definition((input, context) -> new ToolOutcome.Ok(ToolResult.of("ok")),
                inputSchema());
        ToolRuntime runtime = runtime(definition, noopGate(), noopHooks());

        ToolOutcome outcome = runtime.execute(new ToolCall(CallId.create(), new ToolName("missing"),
                JsonValue.ofNull()), context());

        assertThat(outcome).isInstanceOf(ToolOutcome.Failed.class);
    }

    @Test
    void contentIsDerivedFromOutputWhenMissing() {
        ToolDefinition definition = new ToolDefinition(NAME, "echo", inputSchema(), Option.none(),
                (input, context) -> new ToolOutcome.Ok(new ToolResult(Option.some(JsonValue.of("derived")),
                        List.of(), Map.of())), null);

        ToolOutcome outcome = runtime(definition, noopGate(), noopHooks())
                .execute(call(Map.of("text", JsonValue.of("x"))), context());

        ToolResult result = ((ToolOutcome.Ok) outcome).result();
        assertThat(((ToolContent.TextItem) result.content().getFirst()).text()).isEqualTo("derived");
    }

    @Test
    void largeOutputIsBoundedAndStored() {
        InMemoryToolOutput outputStore = new InMemoryToolOutput();
        String large = "line\n".repeat(2_500);
        ToolDefinition definition = definition((input, context) -> new ToolOutcome.Ok(
                new ToolResult(Option.none(), List.of(new ToolContent.TextItem(large)), Map.of())),
                inputSchema());
        ToolRuntime runtime = new ToolRuntime(snapshot(definition), ToolAuthorizer.defaultAuthorizer(),
                noopGate(), noopHooks(), outputStore);

        ToolOutcome outcome = runtime.execute(call(Map.of("text", JsonValue.of("x"))), context());

        ToolResult result = ((ToolOutcome.Ok) outcome).result();
        assertThat(result.metadata()).containsEntry("truncated", "yes");
        assertThat(result.metadata().get("outputPath").toString()).startsWith("artifact://");
        assertThat(outputStore.size()).isEqualTo(1);
    }

    @Test
    void preHookMayRewriteTheCall() {
        ToolDefinition definition = definition((input, context) -> new ToolOutcome.Ok(
                new ToolResult(Option.some(input), List.of(new ToolContent.TextItem("ok")), Map.of())),
                inputSchema());
        Hooks hooks = new Hooks() {
            @Override
            public ToolCall beforeToolCall(ToolContext context, ToolCall call) {
                return new ToolCall(call.id(), call.name(), JsonValue.of(Map.of("text", JsonValue.of("rewritten"))));
            }

            @Override
            public ToolResult afterToolCall(ToolContext context, ToolResult result) {
                return result;
            }
        };

        ToolOutcome outcome = new ToolRuntime(snapshot(definition), ToolAuthorizer.defaultAuthorizer(),
                noopGate(), hooks, new InMemoryToolOutput())
                .execute(call(Map.of("text", JsonValue.of("original"))), context());

        ToolResult result = ((ToolOutcome.Ok) outcome).result();
        assertThat(result.output().orElseThrow()).isEqualTo(JsonValue.of(Map.of("text", JsonValue.of("rewritten"))));
    }

    @Test
    void failingPostHookDoesNotFailTheToolCall() {
        ToolDefinition definition = definition((input, context) -> new ToolOutcome.Ok(
                ToolResult.of("ok")), inputSchema());
        Hooks hooks = new Hooks() {
            @Override
            public ToolCall beforeToolCall(ToolContext context, ToolCall call) {
                return call;
            }

            @Override
            public ToolResult afterToolCall(ToolContext context, ToolResult result) {
                throw new IllegalStateException("hook bug");
            }
        };

        ToolOutcome outcome = runtime(definition, noopGate(), hooks)
                .execute(call(Map.of("text", JsonValue.of("x"))), context());

        assertThat(outcome).isInstanceOf(ToolOutcome.Ok.class);
        assertThat(((ToolOutcome.Ok) outcome).result().content().getFirst())
                .isEqualTo(new ToolContent.TextItem("ok"));
    }

    private static ToolRuntime runtime(ToolDefinition definition, PermissionGate gate, Hooks hooks) {
        return new ToolRuntime(snapshot(definition), ToolAuthorizer.defaultAuthorizer(), gate, hooks,
                new InMemoryToolOutput());
    }

    private static ToolSnapshot snapshot(ToolDefinition definition) {
        return new ToolSnapshot(Map.of(NAME, definition));
    }

    private static ToolDefinition definition(
            de.augmentia.oc.engine.core.contract.tool.ToolExecutor executor, Schema input) {
        return new ToolDefinition(NAME, "echo", input, Option.none(), executor, null);
    }

    private static Schema inputSchema() {
        return Schema.obj(Map.of("text", Schema.str()));
    }

    private static PermissionGate noopGate() {
        return request -> {};
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

    private static ToolCall call(Map<String, JsonValue> input) {
        return new ToolCall(CallId.create(), NAME, JsonValue.of(input));
    }

    private static ToolContext context() {
        return new ToolContext(SessionId.create(), AgentId.create(), MessageId.create(), CallId.create());
    }
}
