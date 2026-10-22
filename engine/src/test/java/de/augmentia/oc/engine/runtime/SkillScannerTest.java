package de.augmentia.oc.engine.runtime;

import de.augmentia.oc.engine.runtime.skill.Skill;
import de.augmentia.oc.engine.runtime.skill.SkillParser;
import de.augmentia.oc.engine.runtime.skill.SkillScanner;
import de.augmentia.oc.engine.testing.FakeFileSystem;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SkillScannerTest {

    private FakeFileSystem fs;
    private SkillScanner scanner;
    private Path root;

    @BeforeEach
    void setUp() {
        fs = new FakeFileSystem();
        scanner = new SkillScanner(fs);
        root = Path.of("/work").toAbsolutePath();
        fs.mkdir(root);
    }

    @Test
    void findsSkillMdAndFlatMarkdownFiles() {
        skillFile("skills/commit/SKILL.md", "commit");
        fs.putText(root.resolve("skills/flat.md"), frontmatter("flat"));
        skillFile("skill/review/SKILL.md", "review");

        List<Path> found = scanner.scan(root);

        assertThat(found).containsExactly(
                root.resolve("skill/review/SKILL.md"),
                root.resolve("skills/commit/SKILL.md"),
                root.resolve("skills/flat.md"));
    }

    @Test
    void hiddenEntriesAreSkipped() {
        skillFile("skills/.hidden/SKILL.md", "hidden");
        skillFile("skills/visible/SKILL.md", "visible");

        assertThat(scanner.scan(root)).containsExactly(root.resolve("skills/visible/SKILL.md"));
    }

    @Test
    void directoryWithSkillMdIsNotSearchedFurther() {
        skillFile("skills/commit/SKILL.md", "commit");
        fs.putText(root.resolve("skills/commit/other.md"), frontmatter("other"));

        assertThat(scanner.scan(root)).containsExactly(root.resolve("skills/commit/SKILL.md"));
    }

    @Test
    void nestedSkillAtDepthTwoIsFound() {
        skillFile("skills/a/b/SKILL.md", "nested");
        assertThat(scanner.scan(root)).containsExactly(root.resolve("skills/a/b/SKILL.md"));
    }

    @Test
    void depthLimitStopsAtSix() {
        skillFile("skills/a/b/c/d/e/f/g/SKILL.md", "too-deep");
        assertThat(scanner.scan(root)).isEmpty();
    }

    @Test
    void flatMarkdownIsOnlyTakenInTheRoot() {
        fs.putText(root.resolve("skills/sub/flat.md"), frontmatter("flat"));
        assertThat(scanner.scan(root)).isEmpty();
    }

    @Test
    void loadSkipsInvalidFiles() {
        skillFile("skills/good/SKILL.md", "good");
        fs.putText(root.resolve("skills/broken/SKILL.md"), "# no frontmatter");

        List<Skill> skills = scanner.load(root, new SkillParser());

        assertThat(skills).extracting(skill -> skill.id().value()).containsExactly("good");
    }

    @Test
    void skillFileListsAtMostTenFiles() {
        skillFile("skills/tool/SKILL.md", "tool");
        for (int i = 0; i < 12; i++) {
            fs.putText(root.resolve("skills/tool/file" + i + ".txt"), "x");
        }

        Skill skill = scanner.load(root, new SkillParser()).getFirst();

        assertThat(skill.files()).hasSize(10);
        assertThat(skill.files()).isSorted();
    }

    @Test
    void flatSkillHasNoFiles() {
        fs.putText(root.resolve("skills/flat.md"), frontmatter("flat"));
        Skill skill = scanner.load(root, new SkillParser()).getFirst();
        assertThat(skill.files()).isEmpty();
    }

    private void skillFile(String relative, String description) {
        fs.putText(root.resolve(relative), frontmatter(description));
    }

    private static String frontmatter(String description) {
        return "---\ndescription: " + description + "\n---\nbody";
    }
}
