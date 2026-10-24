package de.augmentia.oc.engine.core;

import de.augmentia.oc.engine.core.contract.tool.ToolDefinition;
import de.augmentia.oc.engine.core.model.ToolName;
import de.augmentia.oc.engine.runtime.tool.fs.EditTool;
import de.augmentia.oc.engine.runtime.tool.fs.GlobTool;
import de.augmentia.oc.engine.runtime.tool.fs.GrepTool;
import de.augmentia.oc.engine.runtime.tool.fs.PatchTool;
import de.augmentia.oc.engine.runtime.tool.fs.ReadTool;
import de.augmentia.oc.engine.runtime.tool.fs.WriteTool;
import de.augmentia.oc.engine.runtime.tool.shell.ShellTool;
import de.augmentia.oc.engine.runtime.tool.skill.SkillTool;
import de.augmentia.oc.engine.runtime.tool.web.FirecrawlTool;
import de.augmentia.oc.engine.testing.ScriptedHttp;
import de.augmentia.oc.engine.core.port.world.FileSystem;
import de.augmentia.oc.engine.testing.FakeFileSystem;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Golden tests for tool descriptions (PR-10): a change to any tool
 * description is a behavior change and must be visible.
 */
class ToolDescriptionGoldenTest {

    private static final Path ROOT = Path.of("/work").toAbsolutePath();
    private static final FakeFileSystem FS = new FakeFileSystem();
    private static final de.augmentia.oc.engine.runtime.tool.fs.FileAccess ACCESS =
            new de.augmentia.oc.engine.runtime.tool.fs.FileAccess(FS, ROOT, ROOT);

    @Test
    void readDescription() {
        assertThat(ReadTool.definition(FS, ACCESS).description()).isEqualTo("Read a file or directory");
    }

    @Test
    void writeDescription() {
        assertThat(WriteTool.definition(FS, ACCESS).description()).isEqualTo("Write a file");
    }

    @Test
    void editDescription() {
        assertThat(EditTool.definition(FS, ACCESS).description()).isEqualTo("Replace an exact text block");
    }

    @Test
    void patchDescription() {
        assertThat(PatchTool.definition(FS, ACCESS).description()).isEqualTo("Apply a unified diff");
    }

    @Test
    void globDescription() {
        assertThat(GlobTool.definition(FS, ACCESS).description()).isEqualTo("Find files by pattern");
    }

    @Test
    void grepDescription() {
        assertThat(GrepTool.definition(FS, ACCESS).description()).isEqualTo("Search file contents by regex");
    }

    @Test
    void shellDescription() {
        assertThat(ShellTool.definition(new de.augmentia.oc.engine.testing.FakeProcess(), ROOT, null)
                .description()).isEqualTo("Run a shell command");
    }

    @Test
    void skillDescription() {
        assertThat(SkillTool.definition(de.augmentia.oc.engine.runtime.skill.SkillCatalog.empty(),
                java.util.List.of()).description()).isEqualTo("Load a skill by ID");
    }

    @Test
    void websearchDescription() {
        assertThat(FirecrawlTool.definition(new FirecrawlTool(new ScriptedHttp(), ""))
                .description()).isEqualTo("Search the web and return titles, URLs and snippets");
    }
}
