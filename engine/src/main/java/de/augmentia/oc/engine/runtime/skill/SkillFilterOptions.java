package de.augmentia.oc.engine.runtime.skill;

import java.util.List;

/**
 * Options for skill filtering before a prompt is built.
 *
 * <p>A threshold of zero or a blank query disables filtering; the full listed
 * skill set is used instead. {@code alwaysInclude} names skill IDs that are
 * added even when their score is below {@code minScore}.</p>
 */
public record SkillFilterOptions(
        boolean enabled,
        String strategy,
        int topK,
        double minScore,
        int thresholdCount,
        List<String> alwaysInclude) {

    public SkillFilterOptions {
        if (topK < 1) throw new IllegalArgumentException("topK must be >= 1");
        if (minScore < 0.0) throw new IllegalArgumentException("minScore must be >= 0");
        if (thresholdCount < 0) throw new IllegalArgumentException("thresholdCount must be >= 0");
        alwaysInclude = alwaysInclude == null ? List.of() : List.copyOf(alwaysInclude);
        if (strategy == null || strategy.isBlank()) strategy = "bm25";
    }

    public static SkillFilterOptions defaults() {
        return new SkillFilterOptions(true, "bm25", 12, 0.25, 20, List.of());
    }
}
