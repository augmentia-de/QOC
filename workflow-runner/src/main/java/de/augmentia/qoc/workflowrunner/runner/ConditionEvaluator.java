package de.augmentia.qoc.workflowrunner.runner;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Minimal, deterministic condition language used by decision branches and loop
 * exit conditions. (Ported from agentflow ConditionEvaluator.)
 */
public final class ConditionEvaluator {

    private static final Logger LOG = LoggerFactory.getLogger(ConditionEvaluator.class);

    private ConditionEvaluator() {}

    /**
     * Evaluates a condition against a plain string context (legacy).
     */
    public static boolean matches(String condition, String context) {
        String ctx = context == null ? "" : context;
        if (condition == null || condition.isBlank()) {
            return true;
        }
        String c = condition.trim();
        int colon = c.indexOf(':');
        String op = (colon < 0 ? c : c.substring(0, colon)).trim().toLowerCase();
        String arg = colon < 0 ? "" : c.substring(colon + 1);
        return switch (op) {
            case "contains" -> ctx.toLowerCase().contains(arg.toLowerCase());
            case "notcontains" -> !ctx.toLowerCase().contains(arg.toLowerCase());
            case "starts" -> ctx.trim().toLowerCase().startsWith(arg.trim().toLowerCase());
            case "equals" -> ctx.trim().equalsIgnoreCase(arg.trim());
            case "truthy" -> ctx.toLowerCase().contains("true");
            case "falsy" -> ctx.isBlank() || ctx.toLowerCase().contains("false");
            default -> false;
        };
    }

    /**
     * Evaluates a condition against a WorkflowContext.
     * Supports both legacy string-based operators and state-based conditions.
     */
    public static boolean matches(String condition, WorkflowContext ctx) {
        if (condition == null || condition.isBlank()) {
            return true;
        }
        String c = condition.trim();

        // State-basierte Conditions: "status == 'SUCCESS'"
        if (c.contains("==")) {
            String[] parts = c.split("==", 2);
            String left = parts[0].trim();
            String right = parts[1].trim().replace("'", "").replace("\"", "");

            if (left.equals("status") || left.equals("lastStatus")) {
                Object val = ctx.getState("lastStatus");
                boolean result = right.equals(String.valueOf(val));
                LOG.debug("Condition result: {}", result);
                return result;
            }

            // Node-Output-basiert: "node_1.output.classification == 'VIP_ORDER'"
            if (left.startsWith("node_") && left.contains(".output")) {
                String[] pathParts = left.split("\\\\.");
                String nodeId = pathParts[0];
                String field = pathParts.length > 2 ? pathParts[2] : null;

                Object output = ctx.getNodeOutput(nodeId);
                if (output instanceof Map) {
                    Object val = field != null ? ((Map<?, ?>) output).get(field) : output;
                    return right.equals(String.valueOf(val));
                }
                return right.equals(String.valueOf(output));
            }
        }

        // Legacy String-basierte Conditions (Fallback auf lastResult)
        String ctxStr = ctx.getState("lastResult") != null
                ? ctx.getState("lastResult").toString() : "";
        return matches(condition, ctxStr);
    }
}
