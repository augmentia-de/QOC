package de.augmentia.oc.engine.core.contract.tool;

import de.augmentia.oc.engine.core.model.SessionError;

/**
 * Result of one tool execution. {@code Failed} carries the classified error
 * (channel 1), never a {@code ToolFailure} exception or an abort.
 */
public sealed interface ToolOutcome {

    record Ok(ToolResult result) implements ToolOutcome {
        public Ok {
            if (result == null) throw new IllegalArgumentException("result must not be null");
        }
    }

    record Failed(SessionError error) implements ToolOutcome {
        public Failed {
            if (error == null) throw new IllegalArgumentException("error must not be null");
        }

        public Failed(ToolFailure failure) {
            this(toError(failure));
        }

        private static SessionError toError(ToolFailure failure) {
            if (failure == null) throw new IllegalArgumentException("failure must not be null");
            return new SessionError.ToolExecutionError("tool_failure", failure.getMessage());
        }
    }
}
