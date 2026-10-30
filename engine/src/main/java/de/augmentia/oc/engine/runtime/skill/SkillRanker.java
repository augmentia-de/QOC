package de.augmentia.oc.engine.runtime.skill;

import java.util.List;

/**
 * Ranks skills for a user query. Implementations are stateless; the catalog
 * decides when ranking is applied.
 */
public interface SkillRanker {

    /**
     * Returns the candidates in descending score order. Scores are not required
     * to be normalized, but higher is better.
     */
    List<RankedSkill> rank(List<Skill> candidates, String query, SkillFilterOptions options);

    /**
     * A skill together with its ranking score.
     */
    record RankedSkill(Skill skill, double score) {
    }

    /** Returns a ranker that leaves the input order unchanged. */
    static SkillRanker none() {
        return (candidates, query, options) -> candidates == null
                ? List.of()
                : candidates.stream().map(skill -> new RankedSkill(skill, 0.0)).toList();
    }
}
