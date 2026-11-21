package de.augmentia.oc.engine.runtime.tool.undo;

import de.augmentia.oc.engine.core.contract.tool.ToolCall;
import de.augmentia.oc.engine.core.contract.tool.ToolContext;
import de.augmentia.oc.engine.core.contract.tool.ToolDefinition;
import de.augmentia.oc.engine.core.contract.tool.ToolOutcome;
import de.augmentia.oc.engine.core.contract.tool.UndoSpec;
import de.augmentia.oc.engine.core.error.PausedException;
import de.augmentia.oc.engine.core.json.Json;
import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.json.Result;
import de.augmentia.oc.engine.core.model.CallId;
import de.augmentia.oc.engine.core.model.Content;
import de.augmentia.oc.engine.core.model.EventDraft;
import de.augmentia.oc.engine.core.model.IdempotencyKey;
import de.augmentia.oc.engine.core.model.Message;
import de.augmentia.oc.engine.core.model.Seq;
import de.augmentia.oc.engine.core.model.SessionError;
import de.augmentia.oc.engine.core.model.SessionEvent;
import de.augmentia.oc.engine.core.model.SessionId;
import de.augmentia.oc.engine.core.model.StoredEvent;
import de.augmentia.oc.engine.core.model.ToolCard;
import de.augmentia.oc.engine.core.model.ToolContent;
import de.augmentia.oc.engine.core.model.ToolName;
import de.augmentia.oc.engine.core.model.ToolState;
import de.augmentia.oc.engine.core.port.runtime.Clock;
import de.augmentia.oc.engine.core.port.store.Conversation;
import de.augmentia.oc.engine.core.port.store.Journal;
import de.augmentia.oc.engine.runtime.tool.registry.ToolRegistry;
import de.augmentia.oc.engine.runtime.tool.runtime.ToolRuntime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.logging.Logger;

public final class UndoRuntime {

    private static final Logger LOG = Logger.getLogger(UndoRuntime.class.getName());

    private final Conversation conversation;
    private final Journal journal;
    private final ToolRegistry registry;
    private final UndoRegistry undoRegistry;
    private final Supplier<ToolRuntime> toolRuntimeSupplier;
    private final Clock clock;

    public UndoRuntime(Conversation conversation, Journal journal, ToolRegistry registry,
                       UndoRegistry undoRegistry, Supplier<ToolRuntime> toolRuntimeSupplier,
                       Clock clock) {
        if (conversation == null || journal == null || registry == null
                || undoRegistry == null || toolRuntimeSupplier == null) {
            throw new IllegalArgumentException("undo runtime dependencies must not be null");
        }
        this.conversation = conversation;
        this.journal = journal;
        this.registry = registry;
        this.undoRegistry = undoRegistry;
        this.toolRuntimeSupplier = toolRuntimeSupplier;
        this.clock = clock;
    }

    public UndoOutcome executeUndo(JsonValue input, ToolContext ctx, ToolDefinition forward, UndoSpec spec) {
        if (input == null || ctx == null || forward == null || spec == null) {
            throw new IllegalArgumentException("undo input, context, forward tool and spec must not be null");
        }

        String callIdStr;
        try {
            callIdStr = requireString(input, "callId");
        } catch (IllegalArgumentException e) {
            return new UndoOutcome.Failed("", error("invalid_call_id", e.getMessage()));
        }

        CallId originalCallId;
        try {
            originalCallId = CallId.of(callIdStr);
        } catch (IllegalArgumentException e) {
            return new UndoOutcome.Failed(callIdStr, error("invalid_call_id", e.getMessage()));
        }

        SessionId sessionId;
        try {
            String sessionIdStr = optionalString(input, "sessionId");
            sessionId = sessionIdStr == null ? ctx.sessionId() : SessionId.of(sessionIdStr);
        } catch (IllegalArgumentException e) {
            return new UndoOutcome.Failed(callIdStr, error("invalid_session_id", e.getMessage()));
        }

        ToolName forwardName = new ToolName(forward.effectiveName());
        if (!undoRegistry.hasUndo(forwardName)) {
            return new UndoOutcome.NoPattern(forward.effectiveName());
        }

        ToolCard card = findToolCard(sessionId, originalCallId);
        if (card == null) {
            return new UndoOutcome.MissingLogEntry(callIdStr);
        }
        if (!card.name().equals(forwardName)) {
            return new UndoOutcome.NoPattern(forward.effectiveName());
        }
        if (card.state() instanceof ToolState.Failed) {
            return new UndoOutcome.NotApplicable(callIdStr, "original call failed");
        }
        if (!(card.state() instanceof ToolState.Completed completed)) {
            return new UndoOutcome.NotApplicable(callIdStr, "original call has no completed result");
        }

        JsonValue originalInput = card.input();
        JsonValue originalOutput = toolOutput(completed.content());
        Map<String, Object> metadata = completed.metadata();

        if (alreadyUndone(sessionId, originalCallId)) {
            return new UndoOutcome.AlreadyUndone(callIdStr);
        }

        if (!spec.guard().allow(originalInput, originalOutput, metadata)) {
            return new UndoOutcome.GuardRejected(callIdStr, "guard rejected");
        }

        JsonValue undoInput;
        try {
            undoInput = spec.mapper().map(originalInput, originalOutput, metadata);
        } catch (RuntimeException e) {
            return new UndoOutcome.Failed(callIdStr, error("mapper_error",
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
        if (undoInput == null) {
            return new UndoOutcome.Failed(callIdStr, error("mapper_error", "mapper returned null"));
        }

        ToolDefinition compensating = findDefinition(spec.compensatingToolName());
        if (compensating == null) {
            return new UndoOutcome.Failed(callIdStr, error("compensating_tool_missing",
                    "compensating tool not registered: " + spec.compensatingToolName().value()));
        }

        ToolRuntime toolRuntime = toolRuntimeSupplier.get();
        if (toolRuntime == null) {
            return new UndoOutcome.Failed(callIdStr,
                    error("runtime_unavailable", "tool runtime is not initialized"));
        }

        CallId undoCallId = CallId.create();
        ToolContext derivedCtx = new ToolContext(sessionId, ctx.agentId(), ctx.messageId(), undoCallId,
                IdempotencyKey.of(sessionId, undoCallId), ctx.principal(), ctx.toolTokenService());
        ToolCall undoCall = new ToolCall(undoCallId, spec.compensatingToolName(), undoInput);

        ToolOutcome outcome;
        try {
            outcome = toolRuntime.execute(undoCall, derivedCtx);
        } catch (PausedException paused) {
            throw paused;
        } catch (RuntimeException e) {
            return new UndoOutcome.Failed(callIdStr, error("undo_execution_error",
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }

        if (outcome instanceof ToolOutcome.Paused paused) {
            throw new PausedException(paused.approvalId(), paused.approvalUrl());
        }

        String undoToolName = "undo_" + forward.effectiveName();
        appendAudit(sessionId, ctx, undoToolName, originalCallId, undoCallId, outcome);

        if (outcome instanceof ToolOutcome.Ok) {
            return new UndoOutcome.Undone(callIdStr, undoCallId.value(), outcome);
        }
        if (outcome instanceof ToolOutcome.Failed failed) {
            return new UndoOutcome.Failed(callIdStr, failed.error());
        }
        return new UndoOutcome.Failed(callIdStr, error("undo_execution_error",
                "unexpected outcome: " + outcome.getClass().getSimpleName()));
    }

    private ToolCard findToolCard(SessionId sessionId, CallId callId) {
        for (Message message : conversation.messages(sessionId, Seq.zero())) {
            if (message instanceof Message.AssistantMessage assistant) {
                for (Content content : assistant.content()) {
                    if (content instanceof Content.ToolCardContent toolCard
                            && toolCard.card().id().equals(callId)) {
                        return toolCard.card();
                    }
                }
            }
        }
        return null;
    }

    private boolean alreadyUndone(SessionId sessionId, CallId originalCallId) {
        for (StoredEvent event : journal.history(sessionId.value(), Seq.zero())) {
            if (event.payload() instanceof SessionEvent.ToolSucceeded succeeded) {
                Object undoOf = succeeded.metadata().get("undoOf");
                if (originalCallId.value().equals(String.valueOf(undoOf))) {
                    return true;
                }
            }
        }
        return false;
    }

    private ToolDefinition findDefinition(ToolName name) {
        for (ToolDefinition definition : registry.list()) {
            if (new ToolName(definition.effectiveName()).equals(name)) {
                return definition;
            }
        }
        return null;
    }

    private void appendAudit(SessionId sessionId, ToolContext ctx, String undoToolName,
                             CallId originalCallId, CallId undoCallId, ToolOutcome outcome) {
        try {
            Map<String, JsonValue> callInput = Map.of("callId", JsonValue.of(originalCallId.value()));
            journal.append(new EventDraft(sessionId.value(), 1,
                    new SessionEvent.ToolCalled(sessionId, ctx.messageId(), undoCallId,
                            new ToolName(undoToolName), JsonValue.of(callInput), true)));
            if (outcome instanceof ToolOutcome.Ok ok) {
                Map<String, Object> marker = new LinkedHashMap<>(ok.result().metadata());
                marker.put("undoOf", originalCallId.value());
                marker.put("undoTool", undoToolName);
                if (clock != null) marker.put("undoneAt", clock.now().toString());
                List<ToolContent> content = ok.result().content().isEmpty()
                        ? List.of(new ToolContent.TextItem(
                                ok.result().output().map(Json::encode).orElse("")))
                        : ok.result().content();
                journal.append(new EventDraft(sessionId.value(), 1,
                        new SessionEvent.ToolSucceeded(sessionId, ctx.messageId(), undoCallId,
                                content, marker, true)));
            } else if (outcome instanceof ToolOutcome.Failed failed) {
                Map<String, Object> marker = new LinkedHashMap<>(failed.metadata());
                marker.put("undoOf", originalCallId.value());
                marker.put("undoTool", undoToolName);
                if (clock != null) marker.put("undoneAt", clock.now().toString());
                journal.append(new EventDraft(sessionId.value(), 1,
                        new SessionEvent.ToolFailed(sessionId, ctx.messageId(), undoCallId,
                                failed.error(), marker, true)));
            }
        } catch (RuntimeException e) {
            LOG.warning("undo audit append failed for " + originalCallId.value() + ": " + e.getMessage());
        }
    }

    private static JsonValue toolOutput(List<ToolContent> content) {
        if (content == null || content.isEmpty()) return JsonValue.ofNull();
        if (content.size() == 1 && content.get(0) instanceof ToolContent.TextItem text) {
            Result<JsonValue> parsed = Json.parse(text.text());
            if (parsed instanceof Result.Ok<JsonValue> ok) {
                return ok.value();
            }
            return JsonValue.of(text.text());
        }
        List<JsonValue> values = new ArrayList<>();
        for (ToolContent item : content) {
            if (item instanceof ToolContent.TextItem text) {
                values.add(JsonValue.of(text.text()));
            } else if (item instanceof ToolContent.FileItem file) {
                Map<String, JsonValue> value = new LinkedHashMap<>();
                value.put("uri", JsonValue.of(file.uri()));
                value.put("mime", JsonValue.of(file.mime()));
                value.put("name", JsonValue.of(file.name().orElse("")));
                values.add(JsonValue.of(value));
            }
        }
        return JsonValue.of(values);
    }

    private static String requireString(JsonValue input, String key) {
        String value = optionalString(input, key);
        if (value == null) {
            throw new IllegalArgumentException(key + " must be a non-blank string");
        }
        return value;
    }

    private static String optionalString(JsonValue input, String key) {
        if (input instanceof JsonValue.Obj object) {
            JsonValue value = object.values().get(key);
            if (value instanceof JsonValue.Str text && !text.value().isBlank()) {
                return text.value();
            }
        }
        return null;
    }

    private static SessionError.ToolExecutionError error(String code, String message) {
        return new SessionError.ToolExecutionError(code, message == null ? code : message);
    }
}
