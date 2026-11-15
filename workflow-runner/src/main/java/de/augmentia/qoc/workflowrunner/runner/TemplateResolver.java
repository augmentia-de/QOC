package de.augmentia.qoc.workflowrunner.runner;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves template placeholders against a WorkflowContext.
 *
 * <p>Supported syntax:</p>
 * <ul>
 *   <li>{@code ${lastResult}} — text of the previously executed node; a property
 *       path ({@code ${lastResult.output.field}}) is resolved against the
 *       structured result when one is available.</li>
 *   <li>{@code ${state.key}} — a global workflow state variable.</li>
 *   <li>{@code ${node_id.output}} / {@code ${node_id.output.field}} — the
 *       structured output of an earlier node. The {@code .output} segment is
 *       mandatory here, which keeps the dialect unambiguous.</li>
 * </ul>
 *
 * <p>Node IDs may contain hyphens because the workflow editor generates IDs such
 * as {@code erp-prices}.</p>
 *
 * <p>A placeholder that does not match this dialect — for example the
 * Mustache-style {@code {{lastResult}}} — is returned verbatim.</p>
 */
public final class TemplateResolver {

    private static final Logger LOG = LoggerFactory.getLogger(TemplateResolver.class);


    /**
     * Two alternatives: {@code state.<key>} or {@code <node>[.output[.<field>]]}.
     *
     * <p>Named groups keep the two paths apart. An earlier revision shared one
     * group for both, with the result that {@code ${state.key}} could never
     * match — the {@code .output} segment was mandatory.</p>
     */
    private static final Pattern TEMPLATE_PATTERN = Pattern.compile(
            "\\$\\{(?:state\\.(?<stateKey>[a-zA-Z0-9_.-]+)"
                    + "|(?<node>[a-zA-Z0-9_-]+)(?:\\.output(?:\\.(?<field>[a-zA-Z0-9_.-]+))?)?)\\}");

    /** Distinguishes "unknown target" from "target present but value is null". */
    private static final Object RESOLUTION_FAILED = new Object();

    /** Mustache/Handlebars style — never resolved by this dialect. */
    private static final Pattern MUSTACHE_PATTERN = Pattern.compile("\\{\\{[^{}]*}}");

    /** Anything that still looks like a placeholder after the known forms are removed. */
    private static final Pattern SUSPICIOUS_PATTERN = Pattern.compile("\\$\\{[^{}]*}");

    /**
     * Condition paths are written without braces, e.g.
     * {@code node_1.output.classification == 'VIP_ORDER'}.
     */
    private static final Pattern CONDITION_PATH_PATTERN =
            Pattern.compile("^\\s*([a-zA-Z0-9_.-]+?)\\.output(?:\\.|\\s*==|$)");

    /**
     * Statically checks a condition expression against the workflow's node IDs.
     *
     * <p>Conditions use a different syntax from prompts — no braces — so they
     * need their own check. Only the {@code <nodeId>.output...} form is
     * inspected; legacy string operators ({@code contains:foo}) are ignored.</p>
     */
    public static List<String> findConditionProblems(String condition, Set<String> knownNodeIds) {
        if (condition == null || condition.isBlank() || knownNodeIds == null) return List.of();
        Matcher matcher = CONDITION_PATH_PATTERN.matcher(condition);
        if (!matcher.find()) return List.of();
        String nodeId = matcher.group(1);
        if ("lastResult".equals(nodeId) || "state".equals(nodeId)
                || "status".equals(nodeId) || "lastStatus".equals(nodeId)) {
            return List.of();
        }
        // Some conventions prefix node ids with "node_" in conditions
        // (node_n_stress for node n_stress). Accept both forms.
        String candidate = nodeId;
        if (!knownNodeIds.contains(candidate) && candidate.startsWith("node_")) {
            candidate = candidate.substring("node_".length());
        }
        if (!knownNodeIds.contains(candidate)) {
            return List.of("unknown node '" + nodeId + "' in condition '" + condition.trim() + "'");
        }
        return List.of();
    }

    private TemplateResolver() {}

    /**
     * Resolves all placeholders in the given template string.
     * If no placeholder is found, returns the original string unchanged.
     *
     * <p>An unknown node reference is replaced with the bare literal
     * {@code null}. Use {@link #resolveStrict} in new code to fail instead.</p>
     */
    public static String resolve(String template, WorkflowContext ctx) {
        return resolveInternal(template, ctx, false);
    }

    /**
     * Strict variant of {@link #resolve}: an unknown node or unresolvable
     * placeholder raises {@link UnresolvedPlaceholderException} instead of
     * silently injecting the literal {@code null}.
     *
     * <p>Silent substitution is the failure mode that originally let
     * {@code {{lastResult}}} reach an LLM prompt as if it were data.</p>
     */
    public static String resolveStrict(String template, WorkflowContext ctx) {
        return resolveInternal(template, ctx, true, null);
    }

    /**
     * Strict variant that knows the definition's node IDs.
     *
     * <p>This distinguishes two cases that look identical from the context
     * alone:</p>
     * <ul>
     *   <li>the reference names a node that does not exist → hard error, it can
     *       never resolve;</li>
     *   <li>the node exists but produced no output yet (branch not taken, or
     *       executed later) → renders as {@code null} and is logged, because the
     *       template itself is correct.</li>
     * </ul>
     */
    public static String resolveStrict(String template, WorkflowContext ctx, Set<String> knownNodeIds) {
        return resolveInternal(template, ctx, true, knownNodeIds);
    }

    private static String resolveInternal(String template, WorkflowContext ctx, boolean strict) {
        return resolveInternal(template, ctx, strict, null);
    }

    private static String resolveInternal(String template, WorkflowContext ctx, boolean strict,
                                          Set<String> knownNodeIds) {
        LOG.debug("Resolving template: {}", template);
        if (template == null || !template.contains("${")) return template;

        Matcher matcher = TEMPLATE_PATTERN.matcher(template);
        StringBuffer sb = new StringBuffer();

        while (matcher.find()) {
            Object resolved = lookup(matcher, ctx);

            if (resolved == RESOLUTION_FAILED) {
                if (strict && namesExistingNode(matcher, knownNodeIds)) {
                    // The template is correct, the value simply is not available
                    // in this run — keep the historical literal but leave a trace.
                    LOG.warn("Template '{}' references node '{}' which has no output in this run",
                            matcher.group(), matcher.group("node"));
                } else if (strict) {
                    throw new UnresolvedPlaceholderException(
                            "Cannot resolve '" + matcher.group() + "': no such node in this workflow");
                }
                // Historical behaviour: unknown node becomes the literal "null".
                resolved = null;
            }

            String replacement = resolved == null ? "null" : resolved.toString();
            // Escape special regex replacement characters
            replacement = replacement.replace("\\", "\\\\").replace("$", "\\$");
            matcher.appendReplacement(sb, replacement);
        }
        matcher.appendTail(sb);
        LOG.debug("Resolved: {} -> {}", template, sb.toString());
        return sb.toString();
    }

    /**
     * True when the match refers to a node that exists in the definition, so the
     * missing value is a runtime condition rather than a mistake.
     */
    private static boolean namesExistingNode(Matcher matcher, Set<String> knownNodeIds) {
        if (knownNodeIds == null) return false;
        String target = matcher.group("node");
        return target != null && !"lastResult".equals(target) && knownNodeIds.contains(target);
    }

    /**
     * Resolves a nested property path (e.g. "user.email") against an object.
     * Supports Map-based objects.
     */
    public static Object resolveNestedProperty(Object target, String path) {
        if (target == null || path == null || path.isEmpty()) return target;
        String[] parts = path.split("\\.");
        Object current = target;
        for (String part : parts) {
            if (current instanceof Map) {
                current = ((Map<?, ?>) current).get(part);
                if (current == null) return null;
            } else {
                return null;
            }
        }
        return current;
    }

    /**
     * Resolves a template and returns the raw object (not stringified).
     * Useful when the entire value is a single placeholder.
     */
    public static Object resolveToObject(String template, WorkflowContext ctx) {
        if (template == null) return null;
        Matcher matcher = TEMPLATE_PATTERN.matcher(template.trim());
        if (matcher.matches()) {
            Object resolved = lookup(matcher, ctx);
            return resolved == RESOLUTION_FAILED ? null : resolved;
        }
        return template;
    }

    /**
     * Statically checks a template against the workflow's node IDs without a
     * runtime context.
     *
     * <p>This is the compile-time counterpart of {@link #resolve}. Both use the
     * same pattern, so a template that validates here cannot fail at runtime
     * for a syntax reason.</p>
     *
     * @param template      the string to inspect; may be {@code null}
     * @param knownNodeIds  IDs of all nodes in the definition
     * @param allowStateRefs whether {@code ${state.key}} is allowed (state keys
     *                      are dynamic, so only known keys could be checked)
     * @return problems found; empty when the template is fine
     */
    public static List<String> findProblems(String template, Set<String> knownNodeIds,
                                            boolean allowStateRefs) {
        if (template == null || !template.contains("${") && !template.contains("{{")) {
            return List.of();
        }
        List<String> problems = new ArrayList<>();

        // Mustache-style placeholders are a silent no-op at runtime — reject them
        // at compile time so they cannot reach an LLM prompt as literal text.
        Matcher mustache = MUSTACHE_PATTERN.matcher(template);
        while (mustache.find()) {
            problems.add("unsupported placeholder syntax '" + mustache.group()
                    + "' (use ${...}, not {{...}})");
        }

        Matcher matcher = TEMPLATE_PATTERN.matcher(template);
        int matchedChars = 0;
        while (matcher.find()) {
            matchedChars += matcher.group().length();
            String stateKey = matcher.group("stateKey");
            if (stateKey != null) {
                if (!allowStateRefs) {
                    problems.add("state reference '" + matcher.group()
                            + "' is not allowed here");
                }
                continue;
            }
            String target = matcher.group("node");
            if ("lastResult".equals(target)) continue;
            if (knownNodeIds != null && !knownNodeIds.contains(target)) {
                problems.add("unknown node '" + target + "' in '" + matcher.group() + "'");
            }
        }

        // Detect leftovers that look like placeholders but match no branch at all,
        // e.g. a typo such as ${node 1.output} or ${node_1.output.
        String residue = TEMPLATE_PATTERN.matcher(
                MUSTACHE_PATTERN.matcher(template).replaceAll("")).replaceAll("");
        Matcher suspicious = SUSPICIOUS_PATTERN.matcher(residue);
        while (suspicious.find()) {
            problems.add("malformed placeholder '" + suspicious.group()
                    + "' (expected ${node_id.output[.field]} or ${state.key})");
        }
        return problems;
    }

    /**
     * Resolves one match. Returns {@link #RESOLUTION_FAILED} when a referenced
     * node is unknown, otherwise the resolved value (which may be {@code null}).
     */
    private static Object lookup(Matcher matcher, WorkflowContext ctx) {
        String stateKey = matcher.group("stateKey");
        if (stateKey != null) {
            return ctx.getState(stateKey);
        }

        String target = matcher.group("node");
        String field = matcher.group("field");

        // ${lastResult} refers to the previous node's result, not to a node id.
        if ("lastResult".equals(target)) {
            if (field == null) {
                return ctx.getState("lastResult");
            }
            // A property path needs a structured value. Prefer the structured
            // result, fall back to navigating the plain state entry.
            Map<String, Object> structured = ctx.getLastStructuredResult();
            if (!structured.isEmpty()) {
                return resolveNestedProperty(structured, field);
            }
            return resolveNestedProperty(ctx.getState("lastResult"), field);
        }

        Object nodeOutput = ctx.getNodeOutput(target);
        if (nodeOutput == null) {
            return RESOLUTION_FAILED;
        }
        if (field == null || field.isEmpty()) {
            return nodeOutput;
        }
        return resolveNestedProperty(nodeOutput, field);
    }
}
