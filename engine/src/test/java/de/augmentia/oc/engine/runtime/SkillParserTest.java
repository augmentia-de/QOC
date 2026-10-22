package de.augmentia.oc.engine.runtime;

import de.augmentia.oc.engine.core.json.Result;
import de.augmentia.oc.engine.runtime.skill.Skill;
import de.augmentia.oc.engine.runtime.skill.SkillParser;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class SkillParserTest {

    private final SkillParser parser = new SkillParser();

    @Test
    void parsesNameDescriptionAndBody() {
        Result<Skill> result = parser.parse(Path.of("/work/skills/commit/SKILL.md"), """
                ---
                name: Commit
                description: Use when the user asks to commit.
                ---
                # Commit
                1. Read the diff.
                """);

        assertThat(result).isInstanceOf(Result.Ok.class);
        Skill skill = result.valueOrNull();
        assertThat(skill.id().value()).isEqualTo("commit");
        assertThat(skill.name()).isEqualTo("Commit");
        assertThat(skill.description()).isEqualTo("Use when the user asks to commit.");
        assertThat(skill.body()).startsWith("# Commit");
        assertThat(skill.listed()).isTrue();
        assertThat(skill.autoinvoke()).isTrue();
    }

    @Test
    void missingDescriptionMakesTheSkillLoadableButUnlisted() {
        Skill skill = parse("flat.md", """
                ---
                name: Flat
                ---
                body
                """);
        assertThat(skill.id().value()).isEqualTo("flat");
        assertThat(skill.description()).isEmpty();
        assertThat(skill.listed()).isFalse();
    }

    @Test
    void disableModelInvocationHidesTheSkill() {
        Skill skill = parse("hidden.md", """
                ---
                description: hidden
                disable-model-invocation: true
                ---
                body
                """);
        assertThat(skill.autoinvoke()).isFalse();
        assertThat(skill.listed()).isFalse();
    }

    @Test
    void metadataAutoinvokeFalseHidesTheSkill() {
        Skill skill = parse("hidden.md", """
                ---
                description: hidden
                metadata:
                  opencode/autoinvoke: false
                ---
                body
                """);
        assertThat(skill.autoinvoke()).isFalse();
        assertThat(skill.listed()).isFalse();
    }

    @Test
    void quotedValuesAreUnquoted() {
        Skill skill = parse("quoted.md", """
                ---
                name: "Quoted name"
                description: 'Quoted description'
                ---
                body
                """);
        assertThat(skill.name()).isEqualTo("Quoted name");
        assertThat(skill.description()).isEqualTo("Quoted description");
    }

    @Test
    void nameDefaultsToId() {
        Skill skill = parse("review.md", """
                ---
                description: reviews code
                ---
                body
                """);
        assertThat(skill.name()).isEqualTo("review");
    }

    @Test
    void missingFrontmatterIsAnError() {
        Result<Skill> result = parser.parse(Path.of("/work/flat.md"), "# no frontmatter");
        assertThat(result).isInstanceOf(Result.Err.class);
    }

    @Test
    void unclosedFrontmatterIsAnError() {
        Result<Skill> result = parser.parse(Path.of("/work/flat.md"), "---\ndescription: x\n");
        assertThat(result).isInstanceOf(Result.Err.class);
    }

    @Test
    void brokenFrontmatterLineIsAnError() {
        Result<Skill> result = parser.parse(Path.of("/work/flat.md"), "---\nnot a key value\n---\nbody");
        assertThat(result).isInstanceOf(Result.Err.class);
    }

    @Test
    void tooLongDescriptionIsRejected() {
        String description = "x".repeat(SkillParser.MAX_DESCRIPTION + 1);
        Result<Skill> result = parser.parse(Path.of("/work/flat.md"),
                "---\ndescription: " + description + "\n---\nbody");
        assertThat(result).isInstanceOf(Result.Err.class);
    }

    @Test
    void tooLargeBodyIsRejected() {
        String body = "x".repeat(SkillParser.MAX_BODY_BYTES + 1);
        Result<Skill> result = parser.parse(Path.of("/work/flat.md"),
                "---\ndescription: x\n---\n" + body);
        assertThat(result).isInstanceOf(Result.Err.class);
    }

    @Test
    void skillFileWithoutMarkdownNameIsAnError() {
        Result<Skill> result = parser.parse(Path.of("/work/skill.txt"),
                "---\ndescription: x\n---\nbody");
        assertThat(result).isInstanceOf(Result.Err.class);
    }

    @Test
    void relativePathsAreRelativeToTheSkillDirectory() {
        Skill skill = parse("sub/commit/SKILL.md", """
                ---
                description: x
                ---
                body
                """);
        assertThat(skill.baseDirectory()).isEqualTo(Path.of("sub/commit"));
    }

    private Skill parse(String file, String content) {
        Result<Skill> result = parser.parse(Path.of(file), content);
        assertThat(result).as("parse result").isInstanceOf(Result.Ok.class);
        return result.valueOrNull();
    }
}
