package de.augmentia.oc.engine.core.contract.provider;

import de.augmentia.oc.engine.core.model.CallId;
import de.augmentia.oc.engine.core.model.ToolName;

import java.util.List;

public sealed interface ModelMessage {
    record User(List<ModelContent> content) implements ModelMessage {
        public User {
            content = content == null ? List.of() : List.copyOf(content);
        }
    }

    record Assistant(List<ModelContent> content) implements ModelMessage {
        public Assistant {
            content = content == null ? List.of() : List.copyOf(content);
        }
    }

    record ToolResult(CallId callId, ToolName name, OutcomePayload outcome) implements ModelMessage {
        public ToolResult {
            if (callId == null || name == null || outcome == null) {
                throw new IllegalArgumentException("tool result fields must not be null");
            }
        }
    }
}