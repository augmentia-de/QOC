package de.augmentia.oc.engine.runtime;

import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.SessionId;
import de.augmentia.oc.engine.core.port.world.ProcessRunner;
import de.augmentia.oc.engine.runtime.tool.shell.ProcessRegistry;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ProcessRegistryTest {

    @Test
    void assignsStableIdsAndWaitsForCompletion() {
        SessionId session = SessionId.create();
        BlockingProcess process = new BlockingProcess();
        ProcessRegistry registry = new ProcessRegistry();

        ProcessRunner.Handle handle = process.start(spec());
        String id = registry.register(session, "sleep 1", handle);

        assertThat(id).startsWith("sh_");
        assertThat(id).isNotEqualTo(String.valueOf(handle.hashCode()));
        assertThat(registry.await(session, id, Duration.ofMillis(30)).orElseThrow().running()).isTrue();

        process.finish(0, "done");
        ProcessRegistry.Snapshot completed = registry.await(session, id, Duration.ofSeconds(2)).orElseThrow();

        assertThat(completed.state()).isEqualTo(ProcessRegistry.State.COMPLETED);
        assertThat(completed.stdout()).isEqualTo("done");
        assertThat(completed.exit().orElseThrow()).isZero();
        registry.closeAll();
    }

    @Test
    void killTransitionsToKilledAndNotifiesOnce() {
        SessionId session = SessionId.create();
        BlockingProcess process = new BlockingProcess();
        AtomicInteger notifications = new AtomicInteger();
        ProcessRegistry registry = new ProcessRegistry(snapshot -> notifications.incrementAndGet());
        String id = registry.register(session, "sleep 1", process.start(spec()));

        assertThat(registry.kill(session, id)).isTrue();
        assertThat(registry.kill(session, id)).isFalse();
        assertThat(registry.get(session, id).orElseThrow().state()).isEqualTo(ProcessRegistry.State.KILLED);
        assertThat(notifications).hasValue(1);
        registry.closeAll();
    }

    @Test
    void closeSessionKillsAndForgetsOnlyThatSession() {
        SessionId owner = SessionId.create();
        SessionId other = SessionId.create();
        BlockingProcess process = new BlockingProcess();
        ProcessRegistry registry = new ProcessRegistry();
        String id = registry.register(owner, "sleep 1", process.start(spec()));
        String otherId = registry.register(other, "sleep 2", process.start(spec()));

        registry.closeSession(owner);

        assertThat(registry.get(owner, id).isEmpty()).isTrue();
        assertThat(registry.get(other, otherId).isPresent()).isTrue();
        registry.closeAll();
    }

    private static ProcessRunner.ProcessSpec spec() {
        return new ProcessRunner.ProcessSpec(List.of("sh", "-c", "sleep 1"), Option.none(),
                Map.of(), Option.none(), false);
    }

    private static final class BlockingProcess implements ProcessRunner {

        private BlockingHandle handle;

        @Override
        public Handle start(ProcessSpec spec) {
            handle = new BlockingHandle();
            return handle;
        }

        @Override
        public Exit run(ProcessSpec spec, Duration timeout) {
            throw new UnsupportedOperationException("not used");
        }

        private void finish(int code, String stdout) {
            handle.finish(code, stdout);
        }
    }

    private static final class BlockingHandle implements ProcessRunner.Handle {

        private volatile String stdout = "";
        private volatile Option<Integer> exit = Option.none();

        private void finish(int code, String stdout) {
            this.stdout = stdout;
            this.exit = Option.some(code);
        }

        @Override
        public void write(String stdin) {}

        @Override
        public String output() {
            return stdout;
        }

        @Override
        public Option<Integer> exitCode() {
            return exit;
        }

        @Override
        public void kill() {
            // The registry records the terminal state; no OS process exists.
        }

        @Override
        public void close() {}
    }
}
