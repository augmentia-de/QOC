package de.augmentia.oc.engine.runtime.tool.fs;

import de.augmentia.oc.engine.core.contract.tool.ToolOutcome;
import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.testing.FakeFileSystem;
import org.junit.jupiter.api.Test;

import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeleteToolTest {

    private final FakeFileSystem fs = new FakeFileSystem();
    private final FileAccess access = new FileAccess(fs, Path.of("/ws"), Path.of("/ws"));

    @Test
    void deletesAnExistingFile() {
        fs.putText(Path.of("/ws/a.txt"), "a");

        ToolOutcome outcome = DeleteTool.execute(fs, access,
                JsonValue.of(Map.of("path", JsonValue.of("a.txt"))));

        assertThat(outcome).isInstanceOf(ToolOutcome.Ok.class);
        assertThatThrownBy(() -> fs.stat(Path.of("/ws/a.txt")))
                .isInstanceOf(UncheckedIOException.class);
    }

    @Test
    void missingFileIsReportedAsEmpty() {
        ToolOutcome outcome = DeleteTool.execute(fs, access,
                JsonValue.of(Map.of("path", JsonValue.of("missing.txt"))));

        assertThat(outcome).isInstanceOf(ToolOutcome.Empty.class);
    }

    @Test
    void blankPathIsRejected() {
        ToolOutcome outcome = DeleteTool.execute(fs, access,
                JsonValue.of(Map.of("path", JsonValue.of(" "))));

        assertThat(outcome).isInstanceOf(ToolOutcome.Failed.class);
    }
}
