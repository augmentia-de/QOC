package de.augmentia.oc.engine.core.contract.permission;

import de.augmentia.oc.engine.core.model.SessionId;

/**
 * Optional data-level policy applied to tool outputs. Null-safe: a null
 * instance means pass-through (no filtering).
 */
public interface DataPolicy {
    DataPolicyDecision evaluate(SessionId sessionId, String agent, String tool,
                               String resource, Object output);
}
