package de.augmentia.qoc.decisiongate;

import java.util.Objects;

/**
 * The result of the pre-turn gate: how much context and which surfaces the
 * agent gets for this single user turn.
 *
 * <p>The record is deliberately independent of QOC internals. The caller maps
 * {@link ToolProfile}, {@link SkillProfile} and {@link McpProfile} onto the
 * existing {@code ToolSnapshot}, {@code SkillCatalog} and MCP registrations.</p>
 *
 * @param complexity overall task size
 * @param tools      how many tools to expose
 * @param skills     how much of the skill catalogue to inject
 * @param mcp        how much MCP surface to expose
 * @param modelHint  {@code cheap}, {@code default} or {@code strong}
 * @param confidence confidence of the decision, from 0 to 1; 0 for a fallback
 * @param source     short, loggable reason such as {@code decision-model:jev-latest}
 */
public record TaskGateDecision(
        TaskComplexity complexity,
        ToolProfile tools,
        SkillProfile skills,
        McpProfile mcp,
        String modelHint,
        double confidence,
        String source
) {

    public TaskGateDecision {
        Objects.requireNonNull(complexity, "complexity must not be null");
        Objects.requireNonNull(tools, "tools must not be null");
        Objects.requireNonNull(skills, "skills must not be null");
        Objects.requireNonNull(mcp, "mcp must not be null");
        if (modelHint == null || modelHint.isBlank()) {
            throw new IllegalArgumentException("modelHint must not be blank");
        }
        if (Double.isNaN(confidence) || confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence must be between 0 and 1");
        }
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("source must not be blank");
        }
    }

    /**
     * The conservative fallback: keep the surfaces QOC exposes today.
     *
     * <p>A failed, refused or missing decision must never make the agent less
     * capable than before. This profile keeps the standard tool set, every
     * listed skill and the configured MCP servers.</p>
     */
    public static TaskGateDecision fallback(String reason) {
        return new TaskGateDecision(TaskComplexity.MODERATE, ToolProfile.STANDARD,
                SkillProfile.ALL, McpProfile.SELECTED, "default", 0.0, reason);
    }

    /** Maps a complexity answer and the external-system flag to a concrete profile. */
    public static TaskGateDecision forComplexity(TaskComplexity complexity, boolean externalSystem,
                                                 double confidence, String source) {
        return switch (complexity) {
            case TRIVIAL -> new TaskGateDecision(TaskComplexity.TRIVIAL, ToolProfile.MINIMAL,
                    SkillProfile.NONE, McpProfile.NONE, "cheap", confidence, source);
            case MODERATE -> new TaskGateDecision(TaskComplexity.MODERATE, ToolProfile.STANDARD,
                    SkillProfile.CORE, externalSystem ? McpProfile.SELECTED : McpProfile.NONE,
                    "default", confidence, source);
            case COMPLEX -> new TaskGateDecision(TaskComplexity.COMPLEX, ToolProfile.FULL,
                    SkillProfile.TOP_K, externalSystem ? McpProfile.ALL : McpProfile.NONE,
                    "strong", confidence, source);
        };
    }

    /** One-line summary for logs and demo output. */
    public String summary() {
        return String.format(java.util.Locale.ROOT,
                "complexity=%s tools=%s skills=%s mcp=%s model=%s confidence=%.2f source=%s",
                complexity, tools, skills, mcp, modelHint, confidence, source);
    }
}
