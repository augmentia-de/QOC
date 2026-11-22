package de.augmentia.oc.engine.core.contract.permission;

import de.augmentia.oc.engine.core.json.JsonValue;

/**
 * Result of a data-level policy evaluation on a tool output.
 */
public record DataPolicyDecision(DataPolicyEffect effect, JsonValue filteredOutput, String reason) {
    public DataPolicyDecision {
        if (effect == null) effect = DataPolicyEffect.ALLOW;
        if (reason == null) reason = "";
    }

    public static DataPolicyDecision allow() {
        return new DataPolicyDecision(DataPolicyEffect.ALLOW, null, "");
    }

    public static DataPolicyDecision deny(String reason) {
        return new DataPolicyDecision(DataPolicyEffect.DENY, null, reason);
    }

    public static DataPolicyDecision filter(JsonValue filteredOutput, String reason) {
        return new DataPolicyDecision(DataPolicyEffect.FILTER, filteredOutput, reason);
    }
}
