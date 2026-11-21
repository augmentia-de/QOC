package de.augmentia.oc.engine.runtime.tool.undo;

import de.augmentia.oc.engine.core.contract.tool.ToolOutcome;
import de.augmentia.oc.engine.core.model.SessionError;

public sealed interface UndoOutcome {

    record Undone(String originalCallId, String undoCallId, ToolOutcome outcome)
            implements UndoOutcome {}

    record AlreadyUndone(String originalCallId) implements UndoOutcome {}

    record NotApplicable(String originalCallId, String reason) implements UndoOutcome {}

    record NoPattern(String toolName) implements UndoOutcome {}

    record GuardRejected(String originalCallId, String reason) implements UndoOutcome {}

    record MissingLogEntry(String callId) implements UndoOutcome {}

    record Failed(String originalCallId, SessionError error) implements UndoOutcome {}
}
