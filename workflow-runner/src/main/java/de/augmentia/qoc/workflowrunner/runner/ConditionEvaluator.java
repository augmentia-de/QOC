package de.augmentia.qoc.workflowrunner.runner;

import java.util.Map;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Minimal, deterministic condition language used by decision branches and loop
 * exit conditions. (Ported from agentflow ConditionEvaluator.)
 *
 * <p>Supported forms against a {@link WorkflowContext}:</p>
 * <ul>
 *   <li>{@code state.<key> == '<value>'} / {@code state.<key> != '<value>'} —
 *       compare a workflow-state value (stringified) to a literal. Unknown keys
 *       compare as {@code null} ({@code ==} is false, {@code !=} is true).</li>
 *   <li>{@code node_<id>.output == '<value>'} and
 *       {@code node_<id>.output.<field> == '<value>'} — compare a node output
 *       (or a field of a Map output) to a literal. Node ids may contain dots or
 *       hyphens (editor-generated ids), as long as they do not contain the
 *       {@code .output} marker.</li>
 *   <li>{@code status == '<value>'} — alias for the {@code lastStatus} state key
 *       (legacy form, kept for compatibility).</li>
 *   <li>Legacy string operators against the {@code lastResult} state:
 *       {@code contains:}, {@code notcontains:}, {@code starts:},
 *       {@code equals:}, {@code truthy:}, {@code falsy:}.</li>
 * </ul>
 *
 * <p>Dotted state keys such as {@code state.change.contract_impact} resolve the
 * nested Map path ({@code state["change"]["contract_impact"]}). Values are
 * compared after {@link String#valueOf(Object)} on both sides, so numbers and
 * booleans are matched by their string form.</p>
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
     * Supports state-based conditions ({@code state.<key>}, {@code status}),
     * node-output conditions ({@code node_<id>.output[.<field>]}) and the
     * legacy string-based operators (fallback on {@code lastResult}).
     */
    public static boolean matches(String condition, WorkflowContext ctx) {
        if (condition == null || condition.isBlank()) {
            return true;
        }
        String c = condition.trim();

        // Comparison conditions: "X == 'Y'" or "X != 'Y'"
        if (c.contains("==") || c.contains("!=")) {
            boolean negated = c.contains("!=") && !c.contains("==");
            String op = negated ? "!=" : "==";
            int splitAt = c.indexOf(op);
            String left = c.substring(0, splitAt).trim();
            String right = c.substring(splitAt + op.length()).trim()
                    .replace("'", "").replace("\"", "");

            Boolean result = evalComparison(left, right, ctx);
            if (result != null) {
                LOG.debug("Condition result: {}", result);
                return negated != result;
            }
            // Unrecognized left-hand side falls through to the legacy fallback
            // so unknown operators stay deterministic (false), never guessed.
        }

        // Legacy String-basierte Conditions (Fallback auf lastResult)
        String ctxStr = ctx.getState("lastResult") != null
                ? ctx.getState("lastResult").toString() : "";
        return matches(condition, ctxStr);
    }

    /**
     * Evaluates a {@code left op right} comparison against the context.
     * Returns {@code null} when the left-hand side is not a recognized path,
     * letting the caller fall through to the legacy behavior.
     */
    private static Boolean evalComparison(String left, String right, WorkflowContext ctx) {
        // status / lastStatus (legacy alias)
        if (left.equals("status") || left.equals("lastStatus")) {
            return right.equals(String.valueOf(ctx.getState("lastStatus")));
        }

        // state.<key> with optional dotted path into nested maps:
        //   state.contract_impact == 'internal'
        //   state.change.contract_impact == 'external'
        if (left.startsWith("state.")) {
            Object val = resolvePath(ctx::getState, left.substring("state.".length()));
            return right.equals(val == null ? "null" : String.valueOf(val));
        }

        // <nodeId>.output[.<field>] — node output lookup.
        // Node ids may contain dots or hyphens (editor-generated ids such as
        // "erp-prices"); the ".output" marker delimits id from field path.
        if (left.contains(".output")) {
            int outIdx = left.indexOf(".output");
            String nodeId = left.substring(0, outIdx).trim();
            String fieldPath = left.substring(outIdx + ".output".length());
            if (nodeId.isEmpty()) {
                return null;
            }
            Object output = ctx.getNodeOutput(nodeId);
            // Convention fallback: conditions may prefix the node id with
            // "node_" (node_n_stress for node n_stress). Try the stripped
            // form when the verbatim id is not present.
            if (output == null && nodeId.startsWith("node_")) {
                output = ctx.getNodeOutput(nodeId.substring("node_".length()));
                if (output != null) {
                    nodeId = nodeId.substring("node_".length());
                }
            }
            if (output == null) {
                return false;
            }
            if (fieldPath.isBlank() || fieldPath.equals(".")) {
                return right.equals(String.valueOf(output));
            }
            if (output instanceof Map<?, ?> map) {
                Object val = resolvePath(map::get,
                        fieldPath.startsWith(".") ? fieldPath.substring(1) : fieldPath);
                return right.equals(val == null ? "null" : String.valueOf(val));
            }
            // Field requested but output is not a map
            return false;
        }

        return null;
    }

    /**
     * Resolves a dotted path through nested maps. {@code a.b.c} walks
     * {@code get("a") -> get("b") -> get("c")}. A missing segment yields
     * {@code null}.
     */
    private static Object resolvePath(Function<String, Object> get, String dottedPath) {
        if (dottedPath == null || dottedPath.isBlank()) {
            return null;
        }
        Object current = null;
        String[] segments = dottedPath.split("\\.");
        for (int i = 0; i < segments.length; i++) {
            current = (i == 0) ? get.apply(segments[i]) : next(current, segments[i]);
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    private static Object next(Object current, String segment) {
        if (current instanceof Map<?, ?> map) {
            return map.get(segment);
        }
        return null;
    }
}
