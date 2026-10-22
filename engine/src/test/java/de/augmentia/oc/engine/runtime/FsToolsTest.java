package de.augmentia.oc.engine.runtime;

import de.augmentia.oc.engine.core.contract.tool.ToolContext;
import de.augmentia.oc.engine.core.contract.tool.ToolOutcome;
import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.model.AgentId;
import de.augmentia.oc.engine.core.model.CallId;
import de.augmentia.oc.engine.core.model.MessageId;
import de.augmentia.oc.engine.core.model.SessionId;
import de.augmentia.oc.engine.runtime.tool.fs.EditTool;
import de.augmentia.oc.engine.runtime.tool.fs.FileAccess;
import de.augmentia.oc.engine.runtime.tool.fs.GlobTool;
import de.augmentia.oc.engine.runtime.tool.fs.GrepTool;
import de.augmentia.oc.engine.runtime.tool.fs.PatchTool;
import de.augmentia.oc.engine.runtime.tool.fs.ReadTool;
import de.augmentia.oc.engine.runtime.tool.fs.WriteTool;
import de.augmentia.oc.engine.testing.FakeFileSystem;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FsToolsTest {

    private FakeFileSystem fs;
    private FileAccess access;
    private Path root;
    private ToolContext context;

    @BeforeEach
    void setUp() {
        fs = new FakeFileSystem();
        root = Path.of("/work").toAbsolutePath();
        fs.mkdir(root);
        access = new FileAccess(fs, root, root);
        context = new ToolContext(SessionId.create(), AgentId.create(), MessageId.create(), CallId.create());
    }

    @Test
    void readReturnsTextWithLineNumbers() {
        fs.putText(root.resolve("a.txt"), "one\ntwo\nthree");

        ToolOutcome outcome = ReadTool.definition(fs, access).execute().execute(
                args(Map.of("path", JsonValue.of("a.txt"))), context);

        assertThat(outcome).isInstanceOf(ToolOutcome.Ok.class);
        assertThat(text(outcome)).contains("Read file ").contains("1: one").contains("3: three");
    }

    @Test
    void readPaginates() {
        fs.putText(root.resolve("a.txt"), "a\nb\nc\nd");

        ToolOutcome outcome = ReadTool.definition(fs, access).execute().execute(
                args(Map.of("path", JsonValue.of("a.txt"), "offset", JsonValue.of(2L),
                        "limit", JsonValue.of(2L))), context);

        assertThat(text(outcome)).contains("lines 2-3").contains("2: b").contains("3: c")
                .doesNotContain("4: d");
    }

    @Test
    void readRejectsBinaryFiles() {
        fs.write(root.resolve("a.bin"), new byte[]{1, 0, 2});

        ToolOutcome outcome = ReadTool.definition(fs, access).execute().execute(
                args(Map.of("path", JsonValue.of("a.bin"))), context);

        assertThat(outcome).isInstanceOf(ToolOutcome.Failed.class);
    }

    @Test
    void readMissingFileFails() {
        ToolOutcome outcome = ReadTool.definition(fs, access).execute().execute(
                args(Map.of("path", JsonValue.of("missing.txt"))), context);

        assertThat(outcome).isInstanceOf(ToolOutcome.Failed.class);
    }

    @Test
    void readDirectoryListsEntries() {
        fs.putText(root.resolve("dir/a.txt"), "a");
        fs.putText(root.resolve("dir/b.txt"), "b");

        ToolOutcome outcome = ReadTool.definition(fs, access).execute().execute(
                args(Map.of("path", JsonValue.of("dir"))), context);

        assertThat(text(outcome)).contains("Read directory").contains("a.txt").contains("b.txt");
    }

    @Test
    void writeCreatesThenUpdates() {
        ToolOutcome created = WriteTool.definition(fs, access).execute().execute(
                args(Map.of("path", JsonValue.of("a.txt"), "content", JsonValue.of("hello"))), context);

        assertThat(created).isInstanceOf(ToolOutcome.Ok.class);
        assertThat(fs.readText(root.resolve("a.txt"))).isEqualTo("hello");

        ToolOutcome updated = WriteTool.definition(fs, access).execute().execute(
                args(Map.of("path", JsonValue.of("a.txt"), "content", JsonValue.of("world"))), context);

        assertThat(fs.readText(root.resolve("a.txt"))).isEqualTo("world");
        assertThat(updated).isInstanceOf(ToolOutcome.Ok.class);
    }

    @Test
    void editReplacesExactlyOneAnchor() {
        fs.putText(root.resolve("a.txt"), "before middle after");

        ToolOutcome outcome = EditTool.definition(fs, access).execute().execute(
                args(Map.of("path", JsonValue.of("a.txt"), "old", JsonValue.of("middle"),
                        "new", JsonValue.of("center"))), context);

        assertThat(outcome).isInstanceOf(ToolOutcome.Ok.class);
        assertThat(fs.readText(root.resolve("a.txt"))).isEqualTo("before center after");
    }

    @Test
    void editFailsWhenAnchorIsMissingOrAmbiguous() {
        fs.putText(root.resolve("a.txt"), "x x");

        ToolOutcome missing = EditTool.definition(fs, access).execute().execute(
                args(Map.of("path", JsonValue.of("a.txt"), "old", JsonValue.of("nope"),
                        "new", JsonValue.of("y"))), context);
        ToolOutcome ambiguous = EditTool.definition(fs, access).execute().execute(
                args(Map.of("path", JsonValue.of("a.txt"), "old", JsonValue.of("x"),
                        "new", JsonValue.of("y"))), context);

        assertThat(missing).isInstanceOf(ToolOutcome.Failed.class);
        assertThat(ambiguous).isInstanceOf(ToolOutcome.Failed.class);
        assertThat(fs.readText(root.resolve("a.txt"))).isEqualTo("x x");
    }

    @Test
    void globReturnsMatchingFiles() {
        fs.putText(root.resolve("a.txt"), "a");
        fs.putText(root.resolve("dir/b.txt"), "b");

        ToolOutcome outcome = GlobTool.definition(fs, access).execute().execute(
                args(Map.of("pattern", JsonValue.of("*.txt"))), context);

        assertThat(text(outcome)).contains("/work/a.txt").doesNotContain("dir/b.txt");
    }

    @Test
    void grepReturnsMatchingLines() {
        fs.putText(root.resolve("a.txt"), "alpha\nbeta");
        fs.putText(root.resolve("dir/b.txt"), "gamma");

        ToolOutcome outcome = GrepTool.definition(fs, access).execute().execute(
                args(Map.of("pattern", JsonValue.of("a"))), context);

        assertThat(text(outcome)).contains("a.txt:1: alpha").contains("a.txt:2: beta")
                .contains("b.txt:1: gamma");
    }

    @Test
    void patchAppliesAUnifiedDiff() {
        fs.putText(root.resolve("a.txt"), "one\ntwo\nthree\n");
        String patch = """
                --- a/a.txt
                +++ b/a.txt
                @@ -1,3 +1,3 @@
                 one
                -two
                +TWO
                 three
                """;

        ToolOutcome outcome = PatchTool.definition(fs, access).execute().execute(
                args(Map.of("path", JsonValue.of("a.txt"), "patch", JsonValue.of(patch))), context);

        assertThat(outcome).isInstanceOf(ToolOutcome.Ok.class);
        assertThat(fs.readText(root.resolve("a.txt"))).isEqualTo("one\nTWO\nthree\n");
    }

    @Test
    void patchRejectsAMismatchingContext() {
        fs.putText(root.resolve("a.txt"), "one\ntwo\n");
        String patch = "@@ -1,2 +1,2 @@\n one\n-THREE\n+TWO\n";

        ToolOutcome outcome = PatchTool.definition(fs, access).execute().execute(
                args(Map.of("path", JsonValue.of("a.txt"), "patch", JsonValue.of(patch))), context);

        assertThat(outcome).isInstanceOf(ToolOutcome.Failed.class);
        assertThat(fs.readText(root.resolve("a.txt"))).isEqualTo("one\ntwo\n");
    }

    private static JsonValue args(Map<String, JsonValue> values) {
        return JsonValue.of(values);
    }

    private static String text(ToolOutcome outcome) {
        assertThat(outcome).isInstanceOf(ToolOutcome.Ok.class);
        return ((de.augmentia.oc.engine.core.model.ToolContent.TextItem)
                ((ToolOutcome.Ok) outcome).result().content().getFirst()).text();
    }
}
