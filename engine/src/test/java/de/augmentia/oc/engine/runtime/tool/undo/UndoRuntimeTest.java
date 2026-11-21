package de.augmentia.oc.engine.runtime.tool.undo;

import de.augmentia.oc.engine.core.contract.permission.PermissionDenied;
import de.augmentia.oc.engine.core.contract.permission.PermissionGate;
import de.augmentia.oc.engine.core.contract.tool.Hooks;
import de.augmentia.oc.engine.core.contract.tool.ToolCall;
import de.augmentia.oc.engine.core.contract.tool.ToolContext;
import de.augmentia.oc.engine.core.contract.tool.ToolDefinition;
import de.augmentia.oc.engine.core.contract.tool.ToolOptions;
import de.augmentia.oc.engine.core.contract.tool.ToolOutcome;
import de.augmentia.oc.engine.core.contract.tool.ToolResult;
import de.augmentia.oc.engine.core.contract.tool.ToolSnapshot;
import de.augmentia.oc.engine.core.contract.tool.UndoSpec;
import de.augmentia.oc.engine.core.error.PausedException;
import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.json.Schema;
import de.augmentia.oc.engine.core.model.AgentId;
import de.augmentia.oc.engine.core.model.AssistantTime;
import de.augmentia.oc.engine.core.model.CallId;
import de.augmentia.oc.engine.core.model.Content;
import de.augmentia.oc.engine.core.model.Effect;
import de.augmentia.oc.engine.core.model.EventDraft;
import de.augmentia.oc.engine.core.model.Message;
import de.augmentia.oc.engine.core.model.MessageId;
import de.augmentia.oc.engine.core.model.ModelId;
import de.augmentia.oc.engine.core.model.ModelRef;
import de.augmentia.oc.engine.core.model.Money;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.ProviderId;
import de.augmentia.oc.engine.core.model.Rule;
import de.augmentia.oc.engine.core.model.Seq;
import de.augmentia.oc.engine.core.model.SessionError;
import de.augmentia.oc.engine.core.model.SessionEvent;
import de.augmentia.oc.engine.core.model.SessionId;
import de.augmentia.oc.engine.core.model.StoredEvent;
import de.augmentia.oc.engine.core.model.TokenCount;
import de.augmentia.oc.engine.core.model.ToolCard;
import de.augmentia.oc.engine.core.model.ToolContent;
import de.augmentia.oc.engine.core.model.ToolName;
import de.augmentia.oc.engine.core.model.ToolState;
import de.augmentia.oc.engine.core.port.store.Journal;
import de.augmentia.oc.engine.runtime.tool.registry.ToolRegistry;
import de.augmentia.oc.engine.runtime.tool.runtime.ToolAuthorizer;
import de.augmentia.oc.engine.runtime.tool.runtime.ToolRuntime;
import de.augmentia.oc.engine.testing.InMemoryConversation;
import de.augmentia.oc.engine.testing.InMemoryJournal;
import de.augmentia.oc.engine.testing.InMemoryToolOutput;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UndoRuntimeTest {

    private static final ToolName FORWARD = new ToolName("forward");
    private static final ToolName COMPENSATE = new ToolName("compensate");

    private final SessionId session = SessionId.create();
    private final AgentId agent = AgentId.create();
    private final MessageId message = MessageId.create();
    private final CallId forwardCall = CallId.create();
    private final AtomicReference<PermissionGate> gateRef = new AtomicReference<>();

    private InMemoryConversation conversation;
    private InMemoryJournal journal;
    private ToolRegistry registry;
    private UndoRegistry undoRegistry;
    private ToolRuntime toolRuntime;
    private UndoRuntime undoRuntime;
    private JsonValue compensatorInput;
    private JsonValue mapperOutput;
    private boolean compensatorFails;
    private boolean compensatorEmpty;

    @BeforeEach
    void setUp() {
        conversation = new InMemoryConversation();
        journal = new InMemoryJournal();
        registry = new ToolRegistry();
        undoRegistry = new UndoRegistry();
        gateRef.set(request -> {});
        compensatorInput = null;
        mapperOutput = null;
        compensatorFails = false;
        compensatorEmpty = false;

        registry.register(forwardDefinition(UndoSpec.of(COMPENSATE,
                (input, output, metadata) -> {
                    mapperOutput = output;
                    return JsonValue.of(Map.of("original", input));
                })));
        registry.register(compensatorDefinition());
        undoRegistry.rebuildFrom(registry);

        ToolSnapshot snapshot = registry.snapshot(List.of(new Rule("*", "*", Effect.ALLOW)));
        toolRuntime = new ToolRuntime(snapshot, ToolAuthorizer.defaultAuthorizer(),
                request -> gateRef.get().assertAllowed(request), noopHooks(),
                new InMemoryToolOutput(), null, 30);
        undoRuntime = new UndoRuntime(conversation, journal, registry, undoRegistry,
                () -> toolRuntime, null);
    }

    @Test
    void happyPathExecutesTheCompensatingTool() {
        JsonValue original = JsonValue.of(Map.of("path", JsonValue.of("/tmp/x")));
        addCompleted(FORWARD, forwardCall, original,
                List.of(new ToolContent.TextItem("done")), Map.of());

        UndoOutcome outcome = execute();

        assertThat(outcome).isInstanceOf(UndoOutcome.Undone.class);
        UndoOutcome.Undone undone = (UndoOutcome.Undone) outcome;
        assertThat(undone.originalCallId()).isEqualTo(forwardCall.value());
        assertThat(compensatorInput).isEqualTo(JsonValue.of(Map.of("original", original)));
        assertThat(journal.history(session.value(), Seq.zero()))
                .anyMatch(event -> event.payload() instanceof SessionEvent.ToolSucceeded succeeded
                        && forwardCall.value().equals(succeeded.metadata().get("undoOf")));
    }

    @Test
    void missingCallReturnsMissingLogEntry() {
        assertThat(execute()).isInstanceOf(UndoOutcome.MissingLogEntry.class);
    }

    @Test
    void failedOriginalIsNotApplicable() {
        addFailed(FORWARD, forwardCall, JsonValue.of(Map.of()));

        assertThat(execute()).isInstanceOf(UndoOutcome.NotApplicable.class);
    }

    @Test
    void runningOriginalIsNotApplicable() {
        addRunning(FORWARD, forwardCall, JsonValue.of(Map.of()));

        assertThat(execute()).isInstanceOf(UndoOutcome.NotApplicable.class);
    }

    @Test
    void cardForAnotherToolReturnsNoPattern() {
        addCompleted(new ToolName("other"), forwardCall, JsonValue.of(Map.of()),
                List.of(new ToolContent.TextItem("done")), Map.of());

        assertThat(execute()).isInstanceOf(UndoOutcome.NoPattern.class);
    }

    @Test
    void toolWithoutRegisteredUndoPatternReturnsNoPattern() {
        addCompleted(FORWARD, forwardCall, JsonValue.of(Map.of()),
                List.of(new ToolContent.TextItem("done")), Map.of());
        UndoRuntime withoutSpecs = new UndoRuntime(conversation, journal, registry,
                new UndoRegistry(), () -> toolRuntime, null);

        assertThat(withoutSpecs.executeUndo(input(), context(), forwardDef(), spec()))
                .isInstanceOf(UndoOutcome.NoPattern.class);
    }

    @Test
    void guardRejectionIsReported() {
        addCompleted(FORWARD, forwardCall, JsonValue.of(Map.of()),
                List.of(new ToolContent.TextItem("done")), Map.of());
        UndoSpec rejecting = new UndoSpec(COMPENSATE,
                (inp, output, metadata) -> JsonValue.ofNull(),
                (inp, output, metadata) -> false);

        UndoOutcome outcome = undoRuntime.executeUndo(input(), context(), forwardDef(), rejecting);

        assertThat(outcome).isInstanceOf(UndoOutcome.GuardRejected.class);
        assertThat(compensatorInput).isNull();
    }

    @Test
    void mapperExceptionBecomesFailedOutcome() {
        addCompleted(FORWARD, forwardCall, JsonValue.of(Map.of()),
                List.of(new ToolContent.TextItem("done")), Map.of());
        UndoSpec throwing = new UndoSpec(COMPENSATE,
                (inp, output, metadata) -> {
                    throw new IllegalStateException("boom");
                },
                (inp, output, metadata) -> true);

        UndoOutcome.Failed failed = (UndoOutcome.Failed) undoRuntime.executeUndo(
                input(), context(), forwardDef(), throwing);

        assertThat(failed.error().message()).isEqualTo("boom");
    }

    @Test
    void permissionDeniedBecomesFailedOutcome() {
        addCompleted(FORWARD, forwardCall, JsonValue.of(Map.of()),
                List.of(new ToolContent.TextItem("done")), Map.of());
        gateRef.set(request -> {
            throw new PermissionDenied(request, "no");
        });

        UndoOutcome.Failed failed = (UndoOutcome.Failed) execute();

        assertThat(failed.error()).isInstanceOf(SessionError.ToolExecutionError.class);
        assertThat(((SessionError.ToolExecutionError) failed.error()).type())
                .isEqualTo("permission_denied");
    }

    @Test
    void secondUndoIsIdempotent() {
        addCompleted(FORWARD, forwardCall, JsonValue.of(Map.of()),
                List.of(new ToolContent.TextItem("done")), Map.of());

        assertThat(execute()).isInstanceOf(UndoOutcome.Undone.class);
        assertThat(execute()).isInstanceOf(UndoOutcome.AlreadyUndone.class);
    }

    @Test
    void journalMarkerMakesTheCallAlreadyUndone() {
        addCompleted(FORWARD, forwardCall, JsonValue.of(Map.of()),
                List.of(new ToolContent.TextItem("done")), Map.of());
        journal.append(new EventDraft(session.value(), 1,
                new SessionEvent.ToolSucceeded(session, message, CallId.create(),
                        List.of(new ToolContent.TextItem("done")),
                        Map.of("undoOf", forwardCall.value()), true)));

        assertThat(execute()).isInstanceOf(UndoOutcome.AlreadyUndone.class);
    }

    @Test
    void compensatingFailureIsReported() {
        addCompleted(FORWARD, forwardCall, JsonValue.of(Map.of()),
                List.of(new ToolContent.TextItem("done")), Map.of());
        compensatorFails = true;

        UndoOutcome.Failed failed = (UndoOutcome.Failed) execute();

        assertThat(failed.error().message()).isEqualTo("compensator failed");
    }

    @Test
    void emptyCompensatorOutcomeIsReportedAsFailure() {
        addCompleted(FORWARD, forwardCall, JsonValue.of(Map.of()),
                List.of(new ToolContent.TextItem("done")), Map.of());
        compensatorEmpty = true;

        UndoOutcome.Failed failed = (UndoOutcome.Failed) execute();

        assertThat(failed.error().message()).contains("unexpected outcome");
        assertThat(journal.history(session.value(), Seq.zero()))
                .anyMatch(event -> event.payload() instanceof SessionEvent.ToolFailed toolFailed
                        && forwardCall.value().equals(toolFailed.metadata().get("undoOf")));
    }

    @Test
    void pausedCompensatorPropagatesApproval() {
        addCompleted(FORWARD, forwardCall, JsonValue.of(Map.of()),
                List.of(new ToolContent.TextItem("done")), Map.of());
        gateRef.set(request -> {
            throw new PausedException("ap-1", "url");
        });

        assertThatThrownBy(this::execute)
                .isInstanceOf(PausedException.class)
                .hasMessageContaining("ap-1");
    }

    @Test
    void missingCompensatingToolIsReported() {
        addCompleted(FORWARD, forwardCall, JsonValue.of(Map.of()),
                List.of(new ToolContent.TextItem("done")), Map.of());
        UndoSpec missing = UndoSpec.of(new ToolName("missing"),
                (inp, output, metadata) -> JsonValue.of(Map.of()));

        UndoOutcome.Failed failed = (UndoOutcome.Failed) undoRuntime.executeUndo(
                input(), context(), forwardDef(), missing);

        assertThat(((SessionError.ToolExecutionError) failed.error()).type())
                .isEqualTo("compensating_tool_missing");
    }

    @Test
    void missingRuntimeIsReported() {
        addCompleted(FORWARD, forwardCall, JsonValue.of(Map.of()),
                List.of(new ToolContent.TextItem("done")), Map.of());
        UndoRuntime withoutRuntime = new UndoRuntime(conversation, journal, registry,
                undoRegistry, () -> null, null);

        UndoOutcome.Failed failed = (UndoOutcome.Failed) withoutRuntime.executeUndo(
                input(), context(), forwardDef(), spec());

        assertThat(((SessionError.ToolExecutionError) failed.error()).type())
                .isEqualTo("runtime_unavailable");
    }

    @Test
    void missingCallIdIsRejected() {
        UndoOutcome.Failed failed = (UndoOutcome.Failed) undoRuntime.executeUndo(
                JsonValue.of(Map.of()), context(), forwardDef(), spec());

        assertThat(((SessionError.ToolExecutionError) failed.error()).type())
                .isEqualTo("invalid_call_id");
    }

    @Test
    void malformedCallIdIsRejected() {
        UndoOutcome.Failed failed = (UndoOutcome.Failed) undoRuntime.executeUndo(
                JsonValue.of(Map.of("callId", JsonValue.of("nope"))),
                context(), forwardDef(), spec());

        assertThat(((SessionError.ToolExecutionError) failed.error()).type())
                .isEqualTo("invalid_call_id");
    }

    @Test
    void malformedSessionIdIsRejected() {
        UndoOutcome.Failed failed = (UndoOutcome.Failed) undoRuntime.executeUndo(
                JsonValue.of(Map.of("callId", JsonValue.of(forwardCall.value()),
                        "sessionId", JsonValue.of("nope"))),
                context(), forwardDef(), spec());

        assertThat(((SessionError.ToolExecutionError) failed.error()).type())
                .isEqualTo("invalid_session_id");
    }

    @Test
    void nullArgumentsAreRejected() {
        assertThatThrownBy(() -> undoRuntime.executeUndo(null, context(), forwardDef(), spec()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void singleJsonTextContentIsParsed() {
        addCompleted(FORWARD, forwardCall, JsonValue.of(Map.of()),
                List.of(new ToolContent.TextItem("{\"ok\":true}")), Map.of());

        execute();

        assertThat(mapperOutput).isEqualTo(JsonValue.of(Map.of("ok", JsonValue.of(true))));
    }

    @Test
    void multipleContentItemsBecomeAnArray() {
        addCompleted(FORWARD, forwardCall, JsonValue.of(Map.of()),
                List.of(new ToolContent.TextItem("a"),
                        new ToolContent.FileItem("file:///x", "text/plain", Option.none())),
                Map.of());

        execute();

        assertThat(mapperOutput).isInstanceOf(JsonValue.Arr.class);
        assertThat(((JsonValue.Arr) mapperOutput).values()).hasSize(2);
    }

    @Test
    void auditFailureDoesNotFailTheUndo() {
        addCompleted(FORWARD, forwardCall, JsonValue.of(Map.of()),
                List.of(new ToolContent.TextItem("done")), Map.of());
        Journal broken = new Journal() {
            @Override
            public Seq append(EventDraft draft) {
                throw new IllegalStateException("down");
            }

            @Override
            public List<Seq> appendAll(List<EventDraft> drafts) {
                throw new IllegalStateException("down");
            }

            @Override
            public List<StoredEvent> history(String aggregateId, Seq after) {
                return List.of();
            }

            @Override
            public Seq lastSeq(String aggregateId) {
                return Seq.zero();
            }

            @Override
            public List<StoredEvent> historyOfType(String aggregateId, String type, Seq after) {
                return List.of();
            }

            @Override
            public void deleteAggregate(String aggregateId) {
            }

            @Override
            public void deleteOlderThan(Instant cutoff) {
            }
        };
        UndoRuntime runtime = new UndoRuntime(conversation, broken, registry, undoRegistry,
                () -> toolRuntime, null);

        assertThat(runtime.executeUndo(input(), context(), forwardDef(), spec()))
                .isInstanceOf(UndoOutcome.Undone.class);
    }

    private ToolDefinition forwardDefinition(UndoSpec spec) {
        return new ToolDefinition(FORWARD, "forward", Schema.obj(Map.of()), Option.none(),
                (input, context) -> new ToolOutcome.Ok(ToolResult.of("done")),
                ToolOptions.defaults().withUndo(spec));
    }

    private ToolDefinition compensatorDefinition() {
        return new ToolDefinition(COMPENSATE, "compensate",
                Schema.obj(Map.of("original", Schema.any())), Option.none(),
                (input, context) -> {
                    compensatorInput = input;
                    if (compensatorFails) {
                        return new ToolOutcome.Failed(new SessionError.ToolExecutionError(
                                "boom", "compensator failed"));
                    }
                    if (compensatorEmpty) {
                        return new ToolOutcome.Empty("nothing to do");
                    }
                    return new ToolOutcome.Ok(ToolResult.of("compensated"));
                },
                new ToolOptions(Option.none(), Option.of("compensate"), true, false));
    }

    private ToolDefinition forwardDef() {
        for (ToolDefinition definition : registry.list()) {
            if (definition.name().equals(FORWARD)) return definition;
        }
        throw new IllegalStateException("forward tool not registered");
    }

    private UndoSpec spec() {
        return undoRegistry.specOf(FORWARD).orElseThrow();
    }

    private UndoOutcome execute() {
        return undoRuntime.executeUndo(input(), context(), forwardDef(), spec());
    }

    private JsonValue input() {
        return JsonValue.of(Map.of("callId", JsonValue.of(forwardCall.value())));
    }

    private ToolContext context() {
        return new ToolContext(session, agent, message, CallId.create());
    }

    private void addCompleted(ToolName name, CallId callId, JsonValue input,
                              List<ToolContent> content, Map<String, Object> metadata) {
        addCard(new ToolCard(callId, name, input,
                new ToolState.Completed(input, content, metadata), true, Instant.now()));
    }

    private void addFailed(ToolName name, CallId callId, JsonValue input) {
        addCard(new ToolCard(callId, name, input,
                new ToolState.Failed(input, new SessionError.ToolExecutionError("boom", "boom"),
                        Option.none(), Map.of()), true, Instant.now()));
    }

    private void addRunning(ToolName name, CallId callId, JsonValue input) {
        addCard(new ToolCard(callId, name, input, new ToolState.Running(input, Map.of()),
                true, Instant.now()));
    }

    private void addCard(ToolCard card) {
        Message.AssistantMessage assistant = new Message.AssistantMessage(
                MessageId.create(), agent, new ModelRef(ProviderId.create(), ModelId.create()),
                List.of(new Content.ToolCardContent(card)),
                Option.none(), Option.none(), Option.none(), Option.none(),
                Money.zero(), TokenCount.zero(), AssistantTime.started(Instant.now()));
        conversation.add(session, Seq.of(conversation.size(session) + 1L), assistant);
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
}
