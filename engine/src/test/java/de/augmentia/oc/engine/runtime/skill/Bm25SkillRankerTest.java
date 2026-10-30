package de.augmentia.oc.engine.runtime.skill;

import de.augmentia.oc.engine.core.model.SkillId;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class Bm25SkillRankerTest {

    @Test
    void ranksTheMostRelevantSkillFirst() {
        Skill commit = new Skill(SkillId.parse("commit"), "commit", "Use when committing changes", "body",
                Path.of("/skills/commit"), List.of(), true);
        Skill review = new Skill(SkillId.parse("review"), "review", "Review pull requests", "body",
                Path.of("/skills/review"), List.of(), true);

        Bm25SkillRanker ranker = new Bm25SkillRanker();
        List<SkillRanker.RankedSkill> ranked = ranker.rank(List.of(commit, review), "commit changes", SkillFilterOptions.defaults());

        assertThat(ranked).hasSize(2);
        assertThat(ranked.getFirst().skill().id().value()).isEqualTo("commit");
        assertThat(ranked.getFirst().score()).isGreaterThan(ranked.get(1).score());
    }

    @Test
    void returnsZeroScoresForBlankQueries() {
        Skill skill = new Skill(SkillId.parse("commit"), "commit", "Use when committing", "body",
                Path.of("/skills/commit"), List.of(), true);

        List<SkillRanker.RankedSkill> ranked = new Bm25SkillRanker()
                .rank(List.of(skill), "   ", SkillFilterOptions.defaults());

        assertThat(ranked).singleElement().extracting(SkillRanker.RankedSkill::score).isEqualTo(0.0);
    }

    @Test
    void handlesEmptyCandidates() {
        assertThat(new Bm25SkillRanker().rank(List.of(), "query", SkillFilterOptions.defaults())).isEmpty();
    }
}
