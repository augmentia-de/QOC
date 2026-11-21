package de.augmentia.oc.engine.runtime.tool.undo;

import de.augmentia.oc.engine.core.contract.tool.ToolOutcome;
import de.augmentia.oc.engine.core.contract.tool.ToolResult;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.SessionError;
import de.augmentia.oc.engine.core.model.ToolContent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class UndoOutcomeMapper {

    private UndoOutcomeMapper() {}

    static ToolOutcome toToolOutcome(UndoOutcome outcome, String forwardName) {
        if (outcome instanceof UndoOutcome.Undone undone) {
            return marked(undone.outcome(), undone.originalCallId(), forwardName);
        }
        if (outcome instanceof UndoOutcome.AlreadyUndone already) {
            return new ToolOutcome.Ok(new ToolResult(Option.none(),
                    List.of(new ToolContent.TextItem(
                            "Call " + already.originalCallId() + " was already undone.")),
                    Map.of("undoOf", already.originalCallId(), "alreadyUndone", true)));
        }
        if (outcome instanceof UndoOutcome.NotApplicable notApplicable) {
            return failed(notApplicable.originalCallId(), "not_applicable", notApplicable.reason());
        }
        if (outcome instanceof UndoOutcome.NoPattern noPattern) {
            return failed("", "no_undo_pattern", "tool has no undo pattern: " + noPattern.toolName());
        }
        if (outcome instanceof UndoOutcome.GuardRejected rejected) {
            return failed(rejected.originalCallId(), "undo_guard_rejected", rejected.reason());
        }
        if (outcome instanceof UndoOutcome.MissingLogEntry missing) {
            return failed(missing.callId(), "missing_log_entry",
                    "no tool call found for " + missing.callId());
        }
        UndoOutcome.Failed failed = (UndoOutcome.Failed) outcome;
        return new ToolOutcome.Failed(failed.error(),
                failed.originalCallId() == null || failed.originalCallId().isBlank()
                        ? Map.of() : Map.of("undoOf", failed.originalCallId()));
    }

    private static ToolOutcome marked(ToolOutcome outcome, String originalCallId, String forwardName) {
        if (outcome instanceof ToolOutcome.Ok ok) {
            Map<String, Object> metadata = new LinkedHashMap<>(ok.result().metadata());
            metadata.put("undoOf", originalCallId);
            metadata.put("undoTool", "undo_" + forwardName);
            return new ToolOutcome.Ok(new ToolResult(ok.result().output(), ok.result().content(), metadata));
        }
        if (outcome instanceof ToolOutcome.Failed failed) {
            Map<String, Object> metadata = new LinkedHashMap<>(failed.metadata());
            metadata.put("undoOf", originalCallId);
            metadata.put("undoTool", "undo_" + forwardName);
            return new ToolOutcome.Failed(failed.error(), metadata);
        }
        return outcome;
    }

    private static ToolOutcome.Failed failed(String originalCallId, String code, String message) {
        Map<String, Object> metadata = originalCallId == null || originalCallId.isBlank()
                ? Map.of() : Map.of("undoOf", originalCallId);
        return new ToolOutcome.Failed(new SessionError.ToolExecutionError(code,
                message == null ? code : message), metadata);
    }
}
