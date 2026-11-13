package de.augmentia.qoc.workflowrunner.runner;

import de.augmentia.qoc.workflowrunner.model.WorkflowDefinition;
import de.augmentia.qoc.workflowrunner.store.WorkflowJson;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Deterministic context assembly for agent nodes.
 *
 * <p>The policy decides how much of the workflow state is sent to the LLM.
 * {@code FULL} preserves the legacy behavior. {@code FOCUSED}, {@code ARTIFACTS}
 * and {@code CUSTOM} implement context-window hygiene.</p>
 */
public final class ContextAssembler {

    public enum Policy { FULL, FOCUSED, ARTIFACTS, CUSTOM }

    private ContextAssembler() {}

    public static String assemble(WorkflowDefinition def, WorkflowContext ctx,
                                 Policy policy, String task, List<String> customRefs) {
        if (ctx == null) ctx = new WorkflowContext();
        if (policy == null) policy = Policy.FULL;
        if (task == null) task = "";

        StringBuilder sb = new StringBuilder();
        sb.append("## Initial prompt\n");
        String initial = ctx.getInitialPrompt();
        sb.append(initial == null || initial.isBlank() ? "(none)" : initial).append("\n\n");

        sb.append("## Context\n");
        switch (policy) {
            case FULL -> appendFull(def, ctx, sb);
            case FOCUSED -> appendFocused(ctx, sb);
            case ARTIFACTS -> appendArtifacts(ctx, sb);
            case CUSTOM -> appendCustom(ctx, customRefs, sb);
        }

        sb.append("\n## Current task\n");
        sb.append(task.isBlank() ? "Continue with the next step." : task);
        return sb.toString().trim();
    }

    private static void appendFull(WorkflowDefinition def, WorkflowContext ctx, StringBuilder sb) {
        sb.append("### Node outputs\n");
        for (WorkflowDefinition.Node n : def.nodes()) {
            Object out = ctx.getNodeOutput(n.id());
            if (out == null) continue;
            sb.append("#### ").append(n.id()).append(" (").append(n.type()).append(")\n");
            sb.append(renderValue(out)).append("\n\n");
        }
        sb.append("### Workflow state\n");
        ctx.getWorkflowState().entrySet().stream()
                .filter(e -> !e.getKey().startsWith("_"))
                .filter(e -> !"lastResult".equals(e.getKey()))
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> sb.append("- ").append(e.getKey()).append(": ")
                        .append(renderValue(e.getValue())).append('\n'));
    }

    private static void appendFocused(WorkflowContext ctx, StringBuilder sb) {
        List<String> include = ctx.getContextInclude();
        if (include.isEmpty()) {
            // Backward-compatible fallback: last node output + initial prompt.
            sb.append("(no focused references; using last available output)\n");
            return;
        }
        for (String ref : include) {
            if (ref.startsWith("artifact:")) {
                ArtifactRef artifact = ctx.getArtifact(ref.substring("artifact:".length()));
                if (artifact != null) {
                    sb.append("- artifact ").append(artifact.id())
                            .append(" (").append(artifact.type()).append("): ")
                            .append(artifact.summary()).append(" -> ").append(artifact.uri()).append('\n');
                }
            } else {
                Object out = ctx.getNodeOutput(ref);
                if (out != null) {
                    sb.append("#### ").append(ref).append("\n");
                    sb.append(renderValue(out)).append("\n\n");
                }
            }
        }
        if (!ctx.getContextExclude().isEmpty()) {
            sb.append("Excluded: ").append(ctx.getContextExclude()).append('\n');
        }
    }

    private static void appendArtifacts(WorkflowContext ctx, StringBuilder sb) {
        for (ArtifactRef ref : ctx.getArtifacts().values()) {
            sb.append("- ").append(ref.id()).append(" (").append(ref.type()).append("): ")
                    .append(ref.summary()).append(" -> ").append(ref.uri()).append('\n');
        }
        if (ctx.getArtifacts().isEmpty()) {
            sb.append("(no artifacts)\n");
        }
    }

    private static void appendCustom(WorkflowContext ctx, List<String> refs, StringBuilder sb) {
        List<String> selected = refs == null ? List.of() : refs;
        for (String ref : selected) {
            if (ref.startsWith("artifact:")) {
                ArtifactRef artifact = ctx.getArtifact(ref.substring("artifact:".length()));
                if (artifact != null) {
                    sb.append("- artifact ").append(artifact.id())
                            .append(": ").append(artifact.summary()).append(" -> ").append(artifact.uri()).append('\n');
                }
            } else {
                Object out = ctx.getNodeOutput(ref);
                if (out != null) {
                    sb.append("#### ").append(ref).append("\n");
                    sb.append(renderValue(out)).append("\n\n");
                }
            }
        }
    }

    private static String renderValue(Object value) {
        if (value == null) return "";
        if (value instanceof String s) return s;
        try {
            return WorkflowJson.mapper().writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }
}
