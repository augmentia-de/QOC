package de.augmentia.oc.engine.core.contract.tool;
import de.augmentia.oc.engine.core.model.*;
import de.augmentia.oc.engine.core.port.world.ToolTokenService;

/**
 * Per-tool-call context. The idempotency key is stable for the announced call
 * and must be reused by approvals and recovery.
 */
public record ToolContext(SessionId sessionId, AgentId agentId, MessageId messageId,
                          CallId callId, IdempotencyKey idempotencyKey,
                          Principal principal, ToolTokenService toolTokenService) {

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
        if (toolTokenService == null) {
            toolTokenService = ToolTokenService.disabled();
        }
    }

    public ToolContext(SessionId sessionId, AgentId agentId, MessageId messageId, CallId callId) {
        this(sessionId, agentId, messageId, callId, null, Principal.anonymous(), ToolTokenService.disabled());
    }

    public ToolContext(SessionId sessionId, AgentId agentId, MessageId messageId, CallId callId,
                       IdempotencyKey idempotencyKey) {
        this(sessionId, agentId, messageId, callId, idempotencyKey, Principal.anonymous(), ToolTokenService.disabled());
    }

    public ToolContext(SessionId sessionId, AgentId agentId, MessageId messageId, CallId callId,
                       IdempotencyKey idempotencyKey, Principal principal) {
        this(sessionId, agentId, messageId, callId, idempotencyKey, principal, ToolTokenService.disabled());
    }
}
