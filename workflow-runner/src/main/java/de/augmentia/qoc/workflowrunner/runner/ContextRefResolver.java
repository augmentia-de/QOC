package de.augmentia.qoc.workflowrunner.runner;

import java.util.Map;

/**
 * Resolves a {@code contextRef} (possibly a dotted path) against a
 * {@link WorkflowContext}.
 *
 * <p>Supported refs (see plan Phase 0b):</p>
 * <ul>
 *   <li>{@code nodeId} — whole node output</li>
 *   <li>{@code nodeId.field[.sub]} — field from a node output (Map-based)</li>
 *   <li>{@code state.key[.sub]} — workflow state variable</li>
 *   <li>{@code initial} / {@code initial.field[.sub]} — structured initial input</li>
 *   <li>{@code lastResult} / {@code lastResult.field[.sub]} — last structured result</li>
 *   <li>{@code artifact:id} — artifact reference</li>
 * </ul>
 *
 * <p>Prefixed refs ({@code state.}, {@code initial.}, {@code lastResult.},
 * {@code artifact:}) take precedence over node ids.</p>
 */
public final class ContextRefResolver {

    private ContextRefResolver() {}

    /** Reserved targets that may not be used as node ids. */
    public static boolean isReserved(String name) {
        return "state".equals(name) || "initial".equals(name) || "lastResult".equals(name);
    }

    /**
     * Resolves a ref against the context. Returns {@code null} when the ref
     * cannot be resolved (unknown node, missing field, null value).
     */
    public static Object resolve(WorkflowContext ctx, String ref) {
        if (ctx == null || ref == null || ref.isBlank()) return null;

        // artifact:id
        if (ref.startsWith("artifact:")) {
            String id = ref.substring("artifact:".length());
            var artifact = ctx.getArtifact(id);
            return artifact; // may be null
        }

        // state.key[.sub]
        if (ref.startsWith("state.")) {
            String path = ref.substring("state.".length());
            if (path.isEmpty()) return ctx.getWorkflowState();
            String[] parts = path.split("\\.", 2);
            Object base = ctx.getState(parts[0]);
            if (parts.length == 1) return base;
            return TemplateResolver.resolveNestedProperty(base, parts[1]);
        }
        if (ref.equals("state")) {
            return ctx.getWorkflowState();
        }

        // initial[.field]
        if (ref.startsWith("initial.")) {
            String path = ref.substring("initial.".length());
            if (path.isEmpty()) return ctx.getInitialInput();
            return TemplateResolver.resolveNestedProperty(ctx.getInitialInput(), path);
        }
        if (ref.equals("initial")) {
            return ctx.getInitialInput();
        }

        // lastResult[.field]
        if (ref.startsWith("lastResult.")) {
            String path = ref.substring("lastResult.".length());
            if (path.isEmpty()) return ctx.getLastStructuredResult();
            return TemplateResolver.resolveNestedProperty(ctx.getLastStructuredResult(), path);
        }
        if (ref.equals("lastResult")) {
            return ctx.getLastStructuredResult();
        }

        // node[.field]
        int dot = ref.indexOf('.');
        String nodeId = dot < 0 ? ref : ref.substring(0, dot);
        String fieldPath = dot < 0 ? null : ref.substring(dot + 1);

        Object nodeOut = ctx.getNodeOutput(nodeId);
        if (nodeOut == null) return null;
        if (fieldPath == null || fieldPath.isEmpty()) return nodeOut;
        return TemplateResolver.resolveNestedProperty(nodeOut, fieldPath);
    }

    /**
     * Returns true if the ref is syntactically valid (does not throw) and
     * resolves to a non-null value in the given context.
     */
    public static boolean isResolvable(WorkflowContext ctx, String ref) {
        try {
            return resolve(ctx, ref) != null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Validates that a ref refers to a known node or reserved target.
     * Returns a list of problems (empty = ok).
     */
    public static java.util.List<String> findProblems(String ref, java.util.Set<String> knownNodeIds) {
        java.util.List<String> problems = new java.util.ArrayList<>();
        if (ref == null || ref.isBlank()) {
            problems.add("empty context ref");
            return problems;
        }
        if (ref.startsWith("artifact:")) return problems;
        if (ref.startsWith("state.") || ref.equals("state")) return problems;
        if (ref.startsWith("initial.") || ref.equals("initial")) return problems;
        if (ref.startsWith("lastResult.") || ref.equals("lastResult")) return problems;

        int dot = ref.indexOf('.');
        String nodeId = dot < 0 ? ref : ref.substring(0, dot);
        if (isReserved(nodeId)) {
            problems.add("reserved name '" + nodeId + "' used as node id in ref '" + ref + "'");
            return problems;
        }
        if (knownNodeIds != null && !knownNodeIds.contains(nodeId)) {
            problems.add("unknown node '" + nodeId + "' in context ref '" + ref + "'");
        }
        return problems;
    }
}
