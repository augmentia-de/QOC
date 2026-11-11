package de.augmentia.qoc.workflowrunner.runner;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves template placeholders like ${node_1.output} or ${node_1.output.field}
 * against a WorkflowContext.
 */
public final class TemplateResolver {

    private static final Pattern TEMPLATE_PATTERN =
            Pattern.compile("\\$\\{([a-zA-Z0-9_]+)\\.output(\\.([a-zA-Z0-9_.]+))?\\}");

    private TemplateResolver() {}

    /**
     * Resolves all placeholders in the given template string.
     * If no placeholder is found, returns the original string.
     * If a referenced node output is null, returns null.
     */
    public static String resolve(String template, WorkflowContext ctx) {
        if (template == null || !template.contains("${")) return template;

        Matcher matcher = TEMPLATE_PATTERN.matcher(template);
        StringBuffer sb = new StringBuffer();

        while (matcher.find()) {
            String targetNodeId = matcher.group(1);
            String propertyPath = matcher.group(3);
            Object previousOutput = ctx.getNodeOutput(targetNodeId);

            if (previousOutput == null) {
                matcher.appendReplacement(sb, "null");
                continue;
            }

            Object resolved;
            if (propertyPath == null || propertyPath.isEmpty()) {
                resolved = previousOutput;
            } else {
                resolved = resolveNestedProperty(previousOutput, propertyPath);
            }

            String replacement = resolved != null ? resolved.toString() : "null";
            // Escape special regex replacement characters
            replacement = replacement.replace("\\", "\\\\").replace("$", "\\$");
            matcher.appendReplacement(sb, replacement);
        }
        matcher.appendTail(sb);
        return sb.toString();
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
            String targetNodeId = matcher.group(1);
            String propertyPath = matcher.group(3);
            Object previousOutput = ctx.getNodeOutput(targetNodeId);
            if (previousOutput == null) return null;
            if (propertyPath == null || propertyPath.isEmpty()) return previousOutput;
            return resolveNestedProperty(previousOutput, propertyPath);
        }
        return template;
    }
}