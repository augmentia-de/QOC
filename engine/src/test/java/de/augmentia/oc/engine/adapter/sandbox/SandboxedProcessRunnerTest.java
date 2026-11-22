package de.augmentia.oc.engine.adapter.sandbox;

import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.port.world.ProcessRunner;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SandboxedProcessRunnerTest {

    @Test
    void pathGuardRejectsEscape() {
        SandboxConfig cfg = new SandboxConfig("local", null, null, 0, 0, 0, Path.of("/tmp/ws"), null);
        SandboxedProcessRunner runner = new SandboxedProcessRunner(cfg);
        ProcessRunner.ProcessSpec spec = new ProcessRunner.ProcessSpec(
                List.of("echo", "hi"), Option.some(Path.of("/etc")), java.util.Map.of(), Option.none(), false);
        assertThatThrownBy(() -> runner.run(spec, java.time.Duration.ofSeconds(5)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("escapes workspace root");
    }

    @Test
    void localModeRunsEcho() {
        SandboxConfig cfg = new SandboxConfig("local", null, null, 0, 0, 0, Path.of("."), null);
        SandboxedProcessRunner runner = new SandboxedProcessRunner(cfg);
        ProcessRunner.ProcessSpec spec = new ProcessRunner.ProcessSpec(
                List.of("echo", "hello"), Option.none(), java.util.Map.of(), Option.none(), false);
        ProcessRunner.Exit exit = runner.run(spec, java.time.Duration.ofSeconds(5));
        assertThat(exit.code()).isEqualTo(0);
        assertThat(exit.stdout()).contains("hello");
    }
}
