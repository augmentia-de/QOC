package de.augmentia.oc.engine.runtime;

import de.augmentia.oc.engine.adapter.process.JdkProcessRunner;
import de.augmentia.oc.engine.core.contract.tool.ToolContext;
import de.augmentia.oc.engine.core.contract.tool.ToolDefinition;
import de.augmentia.oc.engine.core.contract.tool.ToolOutcome;
import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.model.AgentId;
import de.augmentia.oc.engine.core.model.CallId;
import de.augmentia.oc.engine.core.model.MessageId;
import de.augmentia.oc.engine.core.model.SessionId;
import de.augmentia.oc.engine.core.model.ToolContent;
import de.augmentia.oc.engine.core.port.world.ProcessRunner;
import de.augmentia.oc.engine.runtime.tool.meta.WorktreeTool;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WorktreeToolTest {

    @Test
    void createsListsAndRemovesAWorktree(@TempDir Path directory) throws Exception {
        Assumptions.assumeTrue(gitAvailable(), "git is not installed");
        run(directory, "git", "init", "-q");
        run(directory, "git", "config", "user.email", "test@example.com");
        run(directory, "git", "config", "user.name", "Test");
        Files.writeString(directory.resolve("a.txt"), "a");
        run(directory, "git", "add", ".");
        run(directory, "git", "commit", "-qm", "init");

        WorktreeTool tool = new WorktreeTool(new JdkProcessRunner(), directory);

        ToolOutcome created = execute(WorktreeTool.create(tool),
                Map.of("name", JsonValue.of("feature")));
        assertThat(created).isInstanceOf(ToolOutcome.Ok.class);
        assertThat(directory.resolve(".worktrees/feature/a.txt")).exists();

        ToolOutcome list = execute(WorktreeTool.list(tool), Map.of());
        assertThat(text(list)).contains("feature");

        ToolOutcome removed = execute(WorktreeTool.remove(tool),
                Map.of("directory", JsonValue.of(".worktrees/feature")));
        assertThat(removed).isInstanceOf(ToolOutcome.Ok.class);
        assertThat(directory.resolve(".worktrees/feature")).doesNotExist();
    }

    @Test
    void rejectsDirectoriesOutsideTheWorktreeRoot(@TempDir Path directory) {
        WorktreeTool tool = new WorktreeTool(new JdkProcessRunner(), directory);
        ToolOutcome outcome = execute(WorktreeTool.remove(tool),
                Map.of("directory", JsonValue.of("/tmp/elsewhere")));
        assertThat(outcome).isInstanceOf(ToolOutcome.Failed.class);
        assertThat(((ToolOutcome.Failed) outcome).error().message()).contains("outside");
    }

    @Test
    void missingGitIsACleanFailure(@TempDir Path directory) {
        ProcessRunner failing = new ProcessRunner() {
            @Override
            public Handle start(ProcessSpec spec) {
                throw new java.io.UncheckedIOException(new java.io.IOException("no git"));
            }

            @Override
            public Exit run(ProcessSpec spec, Duration timeout) {
                throw new java.io.UncheckedIOException(new java.io.IOException("no git"));
            }
        };
        WorktreeTool tool = new WorktreeTool(failing, directory);

        ToolOutcome outcome = execute(WorktreeTool.create(tool),
                Map.of("name", JsonValue.of("feature")));

        assertThat(outcome).isInstanceOf(ToolOutcome.Failed.class);
        assertThat(((ToolOutcome.Failed) outcome).error().message()).contains("git is not available");
        assertThat(WorktreeTool.available(failing, directory)).isFalse();
    }

    @Test
    void availabilityRequiresAGitRepository(@TempDir Path directory) throws Exception {
        Assumptions.assumeTrue(gitAvailable(), "git is not installed");
        JdkProcessRunner runner = new JdkProcessRunner();

        assertThat(WorktreeTool.available(runner, directory)).isFalse();

        run(directory, "git", "init", "-q");

        assertThat(WorktreeTool.available(runner, directory)).isTrue();
    }

    private static ToolOutcome execute(ToolDefinition definition, Map<String, JsonValue> input) {
        ToolContext context = new ToolContext(SessionId.create(), AgentId.create(),
                MessageId.create(), CallId.create());
        return definition.execute().execute(JsonValue.of(input), context);
    }

    private static String text(ToolOutcome outcome) {
        return ((ToolContent.TextItem) ((ToolOutcome.Ok) outcome).result().content().getFirst()).text();
    }

    private static boolean gitAvailable() {
        try {
            return new ProcessBuilder("git", "--version").start().waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static void run(Path directory, String... command) throws Exception {
        Process process = new ProcessBuilder(command)
                .directory(directory.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes());
        if (process.waitFor() != 0) throw new IllegalStateException(String.join(" ", command) + ": " + output);
    }
}
