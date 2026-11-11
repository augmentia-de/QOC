package de.augmentia.oc.engine.adapter;

import de.augmentia.oc.engine.adapter.process.JdkProcessRunner;
import de.augmentia.oc.engine.adapter.process.SandboxedProcessRunner;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.port.world.ProcessRunner;
import de.augmentia.oc.engine.core.shell.SandboxMode;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SandboxedProcessRunnerTest {

    private static boolean bwrapAvailable() {
        String bwrap = Files.isExecutable(Path.of("/bin/bwrap")) ? "/bin/bwrap"
                : (Files.isExecutable(Path.of("/usr/bin/bwrap")) ? "/usr/bin/bwrap" : null);
        if (bwrap == null) return false;
        try {
            Process p = new ProcessBuilder(bwrap, "--", "true").start();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    @TempDir
    Path workspace;

    @Test
    void fallsBackWhenBwrapMissing() {
        SandboxedProcessRunner runner = new SandboxedProcessRunner(
                new JdkProcessRunner(), SandboxMode.WORKSPACE_WRITE, workspace, List.of(),
                -1, -1, "/no/such/bwrap", List.of("PATH"));
        ProcessRunner.Exit exit = runner.run(new ProcessRunner.ProcessSpec(
                List.of("sh", "-c", "echo hello"),
                Option.none(), Map.of(), Option.none(), false),
                Duration.ofSeconds(5));
        assertThat(exit.code()).isZero();
        assertThat(exit.stdout()).contains("hello");
    }

    @Test
    void sandboxedRunCapturesStdout() {
        Assumptions.assumeTrue(bwrapAvailable(), "bwrap is not installed");
        SandboxedProcessRunner runner = new SandboxedProcessRunner(
                new JdkProcessRunner(), SandboxMode.WORKSPACE_WRITE, workspace, List.of(),
                -1, -1, "bwrap", List.of("PATH", "HOME", "LANG", "TMPDIR", "TERM"));
        ProcessRunner.Exit exit = runner.run(new ProcessRunner.ProcessSpec(
                List.of("sh", "-c", "echo hello"),
                Option.none(), Map.of(), Option.none(), false),
                Duration.ofSeconds(10));
        assertThat(exit.timedOut()).isFalse();
        assertThat(exit.code()).isZero();
        assertThat(exit.stdout()).contains("hello");
    }

    @Test
    void envSanitizationHidesSecrets() {
        Assumptions.assumeTrue(bwrapAvailable(), "bwrap is not installed");
        SandboxedProcessRunner runner = new SandboxedProcessRunner(
                new JdkProcessRunner(), SandboxMode.WORKSPACE_WRITE, workspace, List.of(),
                -1, -1, "bwrap", List.of("PATH", "HOME", "LANG", "TMPDIR", "TERM"));
        ProcessRunner.Exit exit = runner.run(new ProcessRunner.ProcessSpec(
                List.of("sh", "-c", "env"),
                Option.none(), Map.of("SECRET_TOKEN", "supersecret"), Option.none(), false),
                Duration.ofSeconds(10));
        assertThat(exit.code()).isZero();
        assertThat(exit.stdout()).doesNotContain("supersecret");
        assertThat(exit.stdout()).doesNotContain("SECRET_TOKEN");
    }

    @Test
    void readOnlyModeBlocksWorkspaceWrite() {
        Assumptions.assumeTrue(bwrapAvailable(), "bwrap is not installed");
        SandboxedProcessRunner runner = new SandboxedProcessRunner(
                new JdkProcessRunner(), SandboxMode.READ_ONLY, workspace, List.of(),
                -1, -1, "bwrap", List.of("PATH", "HOME", "LANG", "TMPDIR", "TERM"));
        Path target = workspace.resolve("should-not-exist.txt");
        ProcessRunner.Exit exit = runner.run(new ProcessRunner.ProcessSpec(
                List.of("sh", "-c", "echo data > " + target),
                Option.none(), Map.of(), Option.none(), false),
                Duration.ofSeconds(10));
        // write should fail (non-zero exit) and file should not be created
        assertThat(Files.exists(target)).isFalse();
    }

    @Test
    void workspaceWriteModeAllowsWorkspaceWrite() {
        Assumptions.assumeTrue(bwrapAvailable(), "bwrap is not installed");
        SandboxedProcessRunner runner = new SandboxedProcessRunner(
                new JdkProcessRunner(), SandboxMode.WORKSPACE_WRITE, workspace, List.of(),
                -1, -1, "bwrap", List.of("PATH", "HOME", "LANG", "TMPDIR", "TERM"));
        Path target = workspace.resolve("allowed.txt");
        ProcessRunner.Exit exit = runner.run(new ProcessRunner.ProcessSpec(
                List.of("sh", "-c", "echo data > " + target),
                Option.none(), Map.of(), Option.none(), false),
                Duration.ofSeconds(10));
        assertThat(exit.code()).isZero();
        assertThat(Files.exists(target)).isTrue();
    }

    @Test
    void noneModeDelegates() {
        SandboxedProcessRunner runner = new SandboxedProcessRunner(
                new JdkProcessRunner(), SandboxMode.NONE, workspace, List.of(),
                -1, -1, "bwrap", List.of("PATH"));
        ProcessRunner.Exit exit = runner.run(new ProcessRunner.ProcessSpec(
                List.of("sh", "-c", "echo pass"),
                Option.none(), Map.of(), Option.none(), false),
                Duration.ofSeconds(5));
        assertThat(exit.code()).isZero();
        assertThat(exit.stdout()).contains("pass");
    }

    @Test
    void dangerFullAccessDelegates() {
        SandboxedProcessRunner runner = new SandboxedProcessRunner(
                new JdkProcessRunner(), SandboxMode.DANGER_FULL_ACCESS, workspace, List.of(),
                -1, -1, "bwrap", List.of("PATH"));
        ProcessRunner.Exit exit = runner.run(new ProcessRunner.ProcessSpec(
                List.of("sh", "-c", "echo danger"),
                Option.none(), Map.of(), Option.none(), false),
                Duration.ofSeconds(5));
        assertThat(exit.code()).isZero();
        assertThat(exit.stdout()).contains("danger");
    }
}