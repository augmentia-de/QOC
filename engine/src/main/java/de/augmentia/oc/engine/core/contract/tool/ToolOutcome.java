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

    /**
     * Search/lookup succeeded but produced no results. Distinct from
     * {@link Failed}: the call worked, there is simply nothing to return.
     * The {@code message} explains why (e.g. "no matches for query").
     */
    record Empty(String message) implements ToolOutcome {
        public Empty {
            if (message == null) message = "";
        }
    }

    /**
     * The tool was invoked with arguments the backend cannot process (bad
     * phrase, out-of-range value, unknown enum). The {@code message} tells
     * the caller how to correct the call.
     */
    record InvalidArgument(String message) implements ToolOutcome {
        public InvalidArgument {
            if (message == null) message = "";
        }
    }

    /**
     * The tool's backend was unreachable or returned a transport-level error.
     * Distinct from {@link Failed} (unexpected internal error) so the caller
     * can decide whether a retry is appropriate.
     */
    record Unavailable(String message) implements ToolOutcome {
        public Unavailable {
            if (message == null) message = "";
        }
    }
}
