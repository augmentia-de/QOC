package de.augmentia.oc.engine.core.contract.tool;

import de.augmentia.oc.engine.core.model.SessionError;

import java.util.Map;

/**
 * Result of one tool execution. {@code Failed} carries the classified error
 * (channel 1), never a {@code ToolFailure} exception or an abort. Optional
 * metadata keeps diagnostics such as the process exit code available even
 * when the call failed.
 */
public sealed interface ToolOutcome {

    record Ok(ToolResult result) implements ToolOutcome {
        public Ok {
            if (result == null) throw new IllegalArgumentException("result must not be null");
        }
    }

    record Failed(SessionError error, Map<String, Object> metadata) implements ToolOutcome {
        public Failed {
            if (error == null) throw new IllegalArgumentException("error must not be null");
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        }

        public Failed(SessionError error) {
            this(error, Map.of());
        }

        public Failed(ToolFailure failure) {
            this(toError(failure));
        }

        private static SessionError toError(ToolFailure failure) {
            if (failure == null) throw new IllegalArgumentException("failure must not be null");
            return new SessionError.ToolExecutionError("tool_failure", failure.getMessage());
        }
    }

    /** Tool execution paused pending human approval; session is suspended. */
    record Paused(String approvalId, String approvalUrl) implements ToolOutcome {
        public Paused {
            if (approvalId == null || approvalId.isBlank()) throw new IllegalArgumentException("approvalId must not be blank");
            if (approvalUrl == null) approvalUrl = "";
        }
    }
}
