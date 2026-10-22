package de.augmentia.oc.engine.core.contract.tool;
import de.augmentia.oc.engine.core.model.*;

public record ToolContext(SessionId sessionId, AgentId agentId, MessageId messageId, CallId callId) {
    public ToolContext {
        if (sessionId == null || agentId == null || messageId == null || callId == null) {
            throw new IllegalArgumentException("tool context ids must not be null");
        }
    }
}