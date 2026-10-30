package de.augmentia.oc.engine.runtime.skill;

import de.augmentia.oc.engine.core.eval.PermissionRules;
import de.augmentia.oc.engine.core.eval.PromptLoader;
import de.augmentia.oc.engine.core.model.Effect;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.Rule;
import de.augmentia.oc.engine.core.model.SkillId;
import de.augmentia.oc.engine.core.pattern.Wildcard;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The skill catalog: later sources win (SK-05), listed skills are sorted by ID
 * (SK-06) and rendering follows the instruction block of betrieb/02 §4.
 */
public final class SkillCatalog {

    public static final String GUIDANCE_KEY = "core/skill-guidance";
    public static final String NO_SKILLS = PromptLoader.get("no-skills");

    private final Map<String, Skill> skills;
    private final long epoch;
    private final SkillRanker ranker;

    private SkillCatalog(Map<String, Skill> skills, long epoch, SkillRanker ranker) {
        this.skills = skills;
        this.epoch = epoch;
        this.ranker = ranker == null ? new Bm25SkillRanker() : ranker;
    }

    public static SkillCatalog of(List<Skill> skills) {
        return new SkillCatalog(index(skills), 0, new Bm25SkillRanker());
    }

    /**
     * Builds a catalog with load-time filtering: an empty allow list means
     * "all", deny always wins, and {@code max} caps the result (0 = no cap).
     * IDs use the same wildcard syntax as permissions.
     */
    public static SkillCatalog of(List<Skill> skills, List<String> allow, List<String> deny, int max) {
        List<Skill> safe = skills == null ? List.of() : skills;
        List<Skill> selected = safe.stream()
                .filter(skill -> allow == null || allow.isEmpty()
                        || allow.stream().anyMatch(pattern -> Wildcard.match(skill.id().value(), pattern)))
                .filter(skill -> deny == null
                        || deny.stream().noneMatch(pattern -> Wildcard.match(skill.id().value(), pattern)))
                .sorted(Comparator.comparing(skill -> skill.id().value()))
                .limit(max > 0 ? max : Long.MAX_VALUE)
                .toList();
        return new SkillCatalog(index(selected), 0, new Bm25SkillRanker());
    }

    public static SkillCatalog empty() {
        return new SkillCatalog(Map.of(), 0, new Bm25SkillRanker());
    }

    public SkillCatalog reload(List<Skill> newSkills) {
        return new SkillCatalog(index(newSkills), epoch + 1, ranker);
    }

    public SkillCatalog withRanker(SkillRanker ranker) {
        return new SkillCatalog(skills, epoch, ranker);
    }

    public SkillRanker ranker() {
        return ranker;
    }

    public long epoch() {
        return epoch;
    }

    public Option<Skill> find(SkillId id) {
        if (id == null) return Option.none();
        return Option.of(skills.get(id.value()));
    }

    public Option<Skill> find(String id) {
        return Option.of(skills.get(id));
    }

    public List<Skill> all() {
        return skills.values().stream()
                .sorted(Comparator.comparing(skill -> skill.id().value()))
                .toList();
    }

    /** Visible, listed skills in ID order. */
    public List<Skill> listed() {
        return listed(List.of());
    }

    public List<Skill> listed(List<Rule> rules) {
        List<Rule> safeRules = rules == null ? List.of() : rules;
        return skills.values().stream()
                .filter(Skill::listed)
                .filter(skill -> PermissionRules.evaluate(safeRules, "skill", skill.id().value()) != Effect.DENY)
                .sorted(Comparator.comparing(skill -> skill.id().value()))
                .toList();
    }

    /** The full instruction block (SK-07). */
    public String instructions() {
        return renderInstructions(listed());
    }

    public String instructions(List<Rule> rules) {
        return renderInstructions(listed(rules));
    }

    public String instructions(List<Rule> rules, String query, SkillFilterOptions options) {
        return renderInstructions(relevant(rules, query, options));
    }

    /**
     * The skill block for an explicit selection. {@code null} keeps the
     * previous behaviour and returns the full listed set.
     */
    public String instructions(List<Rule> rules, SkillSelection selection) {
        if (selection == null) return instructions(rules);
        if (selection.mode() == SkillSelection.Mode.NONE) return "";
        return renderInstructions(select(rules, selection));
    }

    /**
     * Visible skills for an explicit selection, in ID order.
     *
     * <p>Permissions are applied first; the selection only narrows the
     * remaining set. {@link SkillSelection#max()} caps the result.</p>
     */
    public List<Skill> select(List<Rule> rules, SkillSelection selection) {
        if (selection == null) return listed(rules);
        List<Skill> selected = switch (selection.mode()) {
            case NONE -> List.of();
            case ALL -> listed(rules);
            case CORE -> core(rules, selection.ids());
            case TOP_K -> relevant(rules, selection.query(), selection.options());
        };
        int max = selection.max();
        if (max > 0 && selected.size() > max) {
            selected = List.copyOf(selected.subList(0, max));
        }
        return List.copyOf(selected);
    }

    private List<Skill> core(List<Rule> rules, List<String> patterns) {
        if (patterns == null || patterns.isEmpty()) return List.of();
        return listed(rules).stream()
                .filter(skill -> patterns.stream()
                        .anyMatch(pattern -> Wildcard.match(skill.id().value(), pattern)))
                .toList();
    }

    /** Visible skills selected for the query, with fallback to the full listed set. */
    public List<Skill> relevant(List<Rule> rules, String query, SkillFilterOptions options) {
        List<Skill> candidates = listed(rules);
        SkillFilterOptions safeOptions = options == null ? SkillFilterOptions.defaults() : options;
        if (!safeOptions.enabled() || query == null || query.isBlank()
                || candidates.size() <= safeOptions.thresholdCount()) {
            return candidates;
        }
        List<SkillRanker.RankedSkill> ranked = ranker.rank(candidates, query, safeOptions);
        if (ranked == null || ranked.isEmpty()) return candidates;

        java.util.Set<String> selectedIds = new java.util.LinkedHashSet<>();
        java.util.List<Skill> selected = new java.util.ArrayList<>();
        for (SkillRanker.RankedSkill rankedSkill : ranked) {
            if (selected.size() >= safeOptions.topK()) break;
            if (rankedSkill.score() < safeOptions.minScore()) continue;
            if (selectedIds.add(rankedSkill.skill().id().value())) {
                selected.add(rankedSkill.skill());
            }
        }
        for (String id : safeOptions.alwaysInclude()) {
            if (selectedIds.contains(id)) continue;
            for (Skill skill : candidates) {
                if (skill.id().value().equals(id)) {
                    selectedIds.add(id);
                    selected.add(skill);
                    break;
                }
            }
        }

        if (selected.isEmpty()) return candidates;
        selected.sort(java.util.Comparator.comparing(skill -> skill.id().value()));
        return List.copyOf(selected);
    }

    public static String renderInstructions(List<Skill> listed) {
        if (listed.isEmpty()) return NO_SKILLS;
        StringBuilder text = new StringBuilder();
        text.append(PromptLoader.get("skill-guidance")).append("\n\n")
                .append("<available_skills>\n");
        for (Skill skill : listed) {
            text.append("  <skill>\n")
                    .append("    <id>").append(escape(skill.id().value())).append("</id>\n")
                    .append("    <name>").append(escape(skill.name())).append("</name>\n")
                    .append("    <description>").append(escape(skill.description())).append("</description>\n")
                    .append("  </skill>\n");
        }
        text.append("</available_skills>");
        return text.toString();
    }

    /** Loaded content in the documented {@code <skill_content>} shape (SK-09). */
    public String content(Skill skill) {
        if (skill == null) throw new IllegalArgumentException("skill must not be null");
        StringBuilder text = new StringBuilder();
        text.append("<skill_content name=\"").append(skill.id().value()).append("\">\n")
                .append("# Skill: ").append(skill.name()).append("\n\n")
                .append(skill.body());
        if (!skill.body().endsWith("\n")) text.append('\n');
        text.append("\nBase directory for this skill: ").append(skill.baseDirectory()).append('\n')
                .append("Relative paths in this skill (e.g. scripts/, reference/) are relative to this base directory.\n");
        if (!skill.files().isEmpty()) {
            text.append("Note: file list is sampled.\n\n<skill_files>\n");
            for (var file : skill.files()) {
                text.append("<file>").append(file).append("</file>\n");
            }
            text.append("</skill_files>\n");
        }
        text.append("</skill_content>");
        return text.toString();
    }

    /** Delta rendering: new, changed and removed IDs (SK-07). */
    public String guidanceDelta(SkillCatalog previous) {
        if (previous == null) return instructions();
        List<String> added = new ArrayList<>();
        List<String> changed = new ArrayList<>();
        List<String> removed = new ArrayList<>();

        for (Skill skill : skills.values()) {
            Skill old = previous.skills.get(skill.id().value());
            if (old == null) {
                added.add(skill.id().value());
            } else if (!old.fingerprint().equals(skill.fingerprint())) {
                changed.add(skill.id().value());
            }
        }
        for (String id : previous.skills.keySet()) {
            if (!skills.containsKey(id)) removed.add(id);
        }

        if (added.isEmpty() && changed.isEmpty() && removed.isEmpty()) return "";
        StringBuilder text = new StringBuilder();
        if (!added.isEmpty()) {
            added.sort(String::compareTo);
            text.append("New skills are available: ").append(String.join(", ", added)).append('\n');
        }
        if (!changed.isEmpty()) {
            changed.sort(String::compareTo);
            text.append("The following skills have changed: ").append(String.join(", ", changed)).append('\n');
        }
        if (!removed.isEmpty()) {
            removed.sort(String::compareTo);
            text.append("The following skill IDs are no longer available and must not be used: ")
                    .append(String.join(", ", removed));
        }
        return text.toString().strip();
    }

    private static Map<String, Skill> index(List<Skill> skills) {
        Map<String, Skill> indexed = new LinkedHashMap<>();
        if (skills != null) {
            for (Skill skill : skills) {
                if (skill == null) continue;
                indexed.put(skill.id().value(), skill);
            }
        }
        return Map.copyOf(indexed);
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
