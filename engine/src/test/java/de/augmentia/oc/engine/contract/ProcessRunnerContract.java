package de.augmentia.oc.engine.contract;

import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.port.world.ProcessRunner;
import de.augmentia.oc.engine.testing.FakeProcess;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Contract of {@link ProcessRunner}. */
class ProcessRunnerContract {

    @Test
    void runReturnsTheScriptedExit() {
        FakeProcess process = new FakeProcess(new ProcessRunner.Exit(0, "out", "err", false));
        ProcessRunner.Exit exit = process.run(spec("ls"), Duration.ofSeconds(5));

        assertThat(exit.code()).isZero();
        assertThat(exit.stdout()).isEqualTo("out");
        assertThat(exit.stderr()).isEqualTo("err");
        assertThat(exit.timedOut()).isFalse();
        assertThat(process.calls()).hasSize(1);
    }

    @Test
    void startReturnsAHandleWithOutputAndExitCode() {
        FakeProcess process = new FakeProcess(new ProcessRunner.Exit(7, "hello", "", false));
        ProcessRunner.Handle handle = process.start(spec("cat"));

        assertThat(handle.output()).isEqualTo("hello");
        assertThat(handle.exitCode().orElse(-1)).isEqualTo(7);
        handle.write("input");
        handle.kill();
        assertThat(handle.exitCode().isEmpty()).isTrue();
        handle.close();
    }

    @Test
    void timedOutExitIsCarriedThrough() {
        FakeProcess process = new FakeProcess(new ProcessRunner.Exit(124, "", "", true));
        assertThat(process.run(spec("sleep"), Duration.ofMillis(1)).timedOut()).isTrue();
    }

    @Test
    void missingScriptIsAnError() {
        FakeProcess process = new FakeProcess();
        assertThatThrownBy(() -> process.run(spec("ls"), Duration.ofSeconds(1)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void invalidArgumentsAreRejected() {
        FakeProcess process = new FakeProcess(new ProcessRunner.Exit(0, "", "", false));
        assertThatThrownBy(() -> process.run(null, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> process.run(spec("ls"), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static ProcessRunner.ProcessSpec spec(String... command) {
        return new ProcessRunner.ProcessSpec(List.of(command), Option.none(), java.util.Map.of(),
                Option.none(), false);
    }
}
