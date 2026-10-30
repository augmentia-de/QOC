package de.augmentia.oc.engine.runtime.skill;

import java.util.List;

/**
 * Per-request selection of the skills that are rendered into the system prompt.
 *
 * <p>This is deliberately separate from {@link SkillCatalog}: the catalog keeps
 * every discovered skill, so the {@code skill} tool can still load a skill that
 * is not listed for the current agent or turn. Permissions stay authoritative;
 * a selection can only narrow what the permission rules already allow.</p>
 *
 * @param mode    how the selection is built
 * @param ids     patterns for {@link Mode#CORE}; ignored otherwise
 * @param query   user prompt for {@link Mode#TOP_K}
 * @param options BM25 options for {@link Mode#TOP_K}
 * @param max     hard cap after selection, {@code 0} means no cap
 */
public record SkillSelection(Mode mode, List<String> ids, String query,
                             SkillFilterOptions options, int max) {

    public enum Mode {
        /** No skill block at all; the skill tool still works. */
        NONE,
        /** Only the given core skill patterns. */
        CORE,
        /** BM25 selection against {@link #query()}. */
        TOP_K,
        /** Every permitted and listed skill. */
        ALL
    }

    public SkillSelection {
        if (mode == null) throw new IllegalArgumentException("mode must not be null");
        ids = ids == null ? List.of() : List.copyOf(ids);
        query = query == null ? "" : query;
        options = options == null ? SkillFilterOptions.defaults() : options;
        if (max < 0) throw new IllegalArgumentException("max must be >= 0");
    }

    public static SkillSelection none() {
        return new SkillSelection(Mode.NONE, List.of(), "", SkillFilterOptions.defaults(), 0);
    }

    public static SkillSelection core(List<String> ids, int max) {
        return new SkillSelection(Mode.CORE, ids, "", SkillFilterOptions.defaults(), max);
    }

    public static SkillSelection topK(String query, SkillFilterOptions options, int max) {
        return new SkillSelection(Mode.TOP_K, List.of(), query, options, max);
    }

    public static SkillSelection all(int max) {
        return new SkillSelection(Mode.ALL, List.of(), "", SkillFilterOptions.defaults(), max);
    }
}
