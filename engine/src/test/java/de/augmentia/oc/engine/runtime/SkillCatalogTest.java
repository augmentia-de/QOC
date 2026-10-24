package de.augmentia.oc.engine.runtime;

import de.augmentia.oc.engine.core.model.Effect;
import de.augmentia.oc.engine.core.model.Rule;
import de.augmentia.oc.engine.core.model.SkillId;
import de.augmentia.oc.engine.runtime.skill.Skill;
import de.augmentia.oc.engine.runtime.skill.SkillCatalog;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SkillCatalogTest {

    @Test
    void laterSourceWinsForTheSameId() {
        Skill first = skill("commit", "first", "body-1", true);
        Skill second = skill("commit", "second", "body-2", true);

        SkillCatalog catalog = SkillCatalog.of(List.of(first, second));

        assertThat(catalog.all()).hasSize(1);
        assertThat(catalog.find("commit").orElseThrow().description()).isEqualTo("second");
    }

    @Test
    void listedRequiresDescriptionAndAutoinvoke() {
        SkillCatalog catalog = SkillCatalog.of(List.of(
                skill("commit", "use when committing", "body", true),
                skill("hidden", "", "body", true),
                skill("manual", "manual only", "body", false)));

        assertThat(catalog.listed()).extracting(skill -> skill.id().value()).containsExactly("commit");
    }

    @Test
    void deniedSkillIsNotListed() {
        SkillCatalog catalog = SkillCatalog.of(List.of(
                skill("commit", "commit", "body", true),
                skill("review", "review", "body", true)));

        List<Rule> rules = List.of(new Rule("skill", "review", Effect.DENY));
        assertThat(catalog.listed(rules)).extracting(skill -> skill.id().value()).containsExactly("commit");
        assertThat(catalog.instructions(rules)).doesNotContain("<id>review</id>");
    }

    @Test
    void emptyCatalogRendersTheNoSkillsText() {
        assertThat(SkillCatalog.empty().instructions()).isEqualTo(SkillCatalog.NO_SKILLS);
    }

    @Test
    void instructionsAreSortedById() {
        SkillCatalog catalog = SkillCatalog.of(List.of(
                skill("review", "review", "body", true),
                skill("commit", "commit", "body", true)));

        String text = catalog.instructions();

        assertThat(text).contains("<available_skills>");
        assertThat(text.indexOf("<id>commit</id>")).isLessThan(text.indexOf("<id>review</id>"));
        assertThat(text).contains("<description>commit</description>");
    }

    @Test
    void contentUsesTheDocumentedShape() {
        Skill skill = new Skill(SkillId.parse("commit"), "Commit", "desc", "# Body\n",
                Path.of("/skills/commit"), List.of(Path.of("/skills/commit/scripts/check.sh")), true);

        String content = SkillCatalog.empty().content(skill);

        assertThat(content).startsWith("<skill_content name=\"commit\">");
        assertThat(content).contains("# Skill: Commit");
        assertThat(content).contains("Base directory for this skill: /skills/commit");
        assertThat(content).contains("<file>/skills/commit/scripts/check.sh</file>");
        assertThat(content).endsWith("</skill_content>");
    }

    @Test
    void contentWithoutFilesHasNoFileList() {
        Skill skill = skill("flat", "flat", "body", true);
        assertThat(SkillCatalog.empty().content(skill)).doesNotContain("<skill_files>");
    }

    @Test
    void guidanceDeltaNamesNewChangedAndRemovedSkills() {
        SkillCatalog previous = SkillCatalog.of(List.of(
                skill("commit", "old", "body", true),
                skill("review", "review", "body", true),
                skill("removed", "removed", "body", true)));
        SkillCatalog current = SkillCatalog.of(List.of(
                skill("commit", "new", "body", true),
                skill("review", "review", "body", true),
                skill("added", "added", "body", true)));

        String delta = current.guidanceDelta(previous);

        assertThat(delta).contains("New skills are available: added");
        assertThat(delta).contains("The following skills have changed: commit");
        assertThat(delta).contains("no longer available and must not be used: removed");
    }

    @Test
    void loadTimeFilterAppliesAllowDenyAndMax() {
        List<Skill> skills = List.of(
                skill("aisdlc-tdd", "tdd", "body", true),
                skill("aisdlc-qa", "qa", "body", true),
                skill("springboot-testing", "spring", "body", true),
                skill("react-hooks", "react", "body", true));

        SkillCatalog catalog = SkillCatalog.of(skills,
                List.of("aisdlc-*"), List.of("*-qa"), 1);

        assertThat(catalog.all()).extracting(skill -> skill.id().value())
                .containsExactly("aisdlc-tdd");
    }

    @Test
    void emptyAllowListMeansAllSkills() {
        SkillCatalog catalog = SkillCatalog.of(List.of(
                skill("a", "a", "body", true),
                skill("b", "b", "body", true)), List.of(), List.of("b"), 0);

        assertThat(catalog.all()).extracting(skill -> skill.id().value()).containsExactly("a");
    }

    @Test
    void unchangedCatalogHasEmptyDelta() {
        SkillCatalog catalog = SkillCatalog.of(List.of(skill("commit", "commit", "body", true)));
        assertThat(catalog.guidanceDelta(catalog)).isEmpty();
    }

    @Test
    void reloadAdvancesTheEpoch() {
        SkillCatalog catalog = SkillCatalog.empty();
        SkillCatalog reloaded = catalog.reload(List.of(skill("commit", "commit", "body", true)));

        assertThat(reloaded.epoch()).isEqualTo(1);
        assertThat(catalog.epoch()).isZero();
        assertThat(reloaded.all()).hasSize(1);
    }

    private static Skill skill(String id, String description, String body, boolean autoinvoke) {
        return new Skill(SkillId.parse(id), id, description, body, Path.of("/skills", id),
                List.of(), autoinvoke);
    }
}
