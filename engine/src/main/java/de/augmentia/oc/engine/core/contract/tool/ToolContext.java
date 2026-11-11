package de.augmentia.oc.engine.core.contract.tool;
import de.augmentia.oc.engine.core.model.*;

/**
 * Per-tool-call context. The idempotency key is stable for the announced call
 * and must be reused by approvals and recovery.
 */
public record ToolContext(SessionId sessionId, AgentId agentId, MessageId messageId,
                          CallId callId, IdempotencyKey idempotencyKey,
                          Principal principal) {

    public ToolContext {
        if (sessionId == null || agentId == null || messageId == null || callId == null) {
            throw new IllegalArgumentException("tool context ids must not be null");
        }
        if (idempotencyKey == null) {
            idempotencyKey = IdempotencyKey.of(sessionId, callId);
        }
        if (principal == null) {
            principal = Principal.anonymous();
        }
    }

    public ToolContext(SessionId sessionId, AgentId agentId, MessageId messageId, CallId callId) {
        this(sessionId, agentId, messageId, callId, null, Principal.anonymous());
    }

    public ToolContext(SessionId sessionId, AgentId agentId, MessageId messageId, CallId callId,
                       IdempotencyKey idempotencyKey) {
        this(sessionId, agentId, messageId, callId, idempotencyKey, Principal.anonymous());
    }
}
