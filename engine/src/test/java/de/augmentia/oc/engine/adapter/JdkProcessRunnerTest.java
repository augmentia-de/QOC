package de.augmentia.oc.engine.adapter;

import de.augmentia.oc.engine.adapter.process.JdkProcessRunner;
import de.augmentia.oc.engine.core.port.world.ProcessRunner;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JdkProcessRunnerTest {

    private final JdkProcessRunner runner = new JdkProcessRunner();

    @Test
    void runCapturesStdoutAndExitCode() {
        ProcessRunner.Exit exit = runner.run(new ProcessRunner.ProcessSpec(
                List.of("sh", "-c", "echo hello"), de.augmentia.oc.engine.core.model.Option.none(),
                Map.of(), de.augmentia.oc.engine.core.model.Option.none(), false),
                Duration.ofSeconds(5));

        assertThat(exit.timedOut()).isFalse();
        assertThat(exit.code()).isZero();
        assertThat(exit.stdout()).contains("hello");
    }

    @Test
    void runFeedsStdin() {
        ProcessRunner.Exit exit = runner.run(new ProcessRunner.ProcessSpec(
                List.of("sh", "-c", "cat"),
                de.augmentia.oc.engine.core.model.Option.none(), Map.of(),
                de.augmentia.oc.engine.core.model.Option.some("input"), false),
                Duration.ofSeconds(5));

        assertThat(exit.stdout()).contains("input");
    }

    @Test
    void runTimeoutIsReported() {
        ProcessRunner.Exit exit = runner.run(new ProcessRunner.ProcessSpec(
                List.of("sh", "-c", "sleep 5"),
                de.augmentia.oc.engine.core.model.Option.none(), Map.of(),
                de.augmentia.oc.engine.core.model.Option.none(), false),
                Duration.ofMillis(100));

        assertThat(exit.timedOut()).isTrue();
    }

    @Test
    void startHandleAccumulatesOutput() throws Exception {
        ProcessRunner.Handle handle = runner.start(new ProcessRunner.ProcessSpec(
                List.of("sh", "-c", "echo started"),
                de.augmentia.oc.engine.core.model.Option.none(), Map.of(),
                de.augmentia.oc.engine.core.model.Option.none(), false));
        long deadline = System.currentTimeMillis() + 2_000;
        while (!handle.output().contains("started") && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertThat(handle.output()).contains("started");
        handle.close();
    }
}
