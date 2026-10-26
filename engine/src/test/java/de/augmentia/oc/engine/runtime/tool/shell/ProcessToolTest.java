package de.augmentia.oc.engine.runtime.tool.shell;

import de.augmentia.oc.engine.core.contract.tool.ToolContext;
import de.augmentia.oc.engine.core.contract.tool.ToolOutcome;
import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.model.AgentId;
import de.augmentia.oc.engine.core.model.CallId;
import de.augmentia.oc.engine.core.model.MessageId;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.SessionId;
import de.augmentia.oc.engine.core.port.world.ProcessRunner;
import de.augmentia.oc.engine.testing.FakeProcess;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ProcessToolTest {

    @Test
    void listsAndPrintsACapturedProcess() {
        SessionId session = SessionId.create();
        ProcessRegistry registry = new ProcessRegistry();
        FakeProcess process = new FakeProcess(new ProcessRunner.Exit(0, "out", "err", false));
        String id = registry.register(session, "echo hi", process.start(spec()));
        await(registry, session, id);

        ToolOutcome list = execute(registry, session, "list", null);
        ToolOutcome output = execute(registry, session, "output", id);
        ToolOutcome status = execute(registry, session, "status", id);

        assertThat(text(list)).contains(id).contains("completed");
        assertThat(text(output)).contains("out").contains("[stderr]").contains("err");
        assertThat(text(status)).contains("state: completed").contains("exit: 0");
        registry.closeAll();
    }

    @Test
    void waitsAndKills() {
        SessionId session = SessionId.create();
        ProcessRegistry registry = new ProcessRegistry();
        BlockingProcess process = new BlockingProcess();
        String id = registry.register(session, "sleep 1", process.start(spec()));

        ToolOutcome running = execute(registry, session, "wait", id, 20);
        ToolOutcome killed = execute(registry, session, "kill", id);

        assertThat(text(running)).contains("Still running");
        assertThat(((ToolOutcome.Ok) killed).result().metadata()).containsEntry("killed", true);
        assertThat(registry.get(session, id).orElseThrow().state())
                .isEqualTo(ProcessRegistry.State.KILLED);
        registry.closeAll();
    }

    private static ToolOutcome execute(ProcessRegistry registry, SessionId session,
                                       String action, String id) {
        return execute(registry, session, action, id, null);
    }

    private static ToolOutcome execute(ProcessRegistry registry, SessionId session,
                                       String action, String id, Integer timeout) {
        var values = new java.util.LinkedHashMap<String, JsonValue>();
        values.put("action", JsonValue.of(action));
        if (id != null) values.put("id", JsonValue.of(id));
        if (timeout != null) values.put("timeout", JsonValue.of(timeout.longValue()));
        return ProcessTool.definition(registry).execute().execute(JsonValue.of(values),
                new ToolContext(session, AgentId.create(), MessageId.create(), CallId.create()));
    }

    private static String text(ToolOutcome outcome) {
        return ((de.augmentia.oc.engine.core.model.ToolContent.TextItem)
                ((ToolOutcome.Ok) outcome).result().content().getFirst()).text();
    }

    private static void await(ProcessRegistry registry, SessionId session, String id) {
        long deadline = System.currentTimeMillis() + 2_000;
        while (registry.get(session, id).orElseThrow().running()
                && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static ProcessRunner.ProcessSpec spec() {
        return new ProcessRunner.ProcessSpec(List.of("sh", "-c", "sleep 1"), Option.none(),
                Map.of(), Option.none(), false);
    }

    private static final class BlockingProcess implements ProcessRunner {

        @Override
        public Handle start(ProcessSpec spec) {
            return new BlockingHandle();
        }

        @Override
        public Exit run(ProcessSpec spec, Duration timeout) {
            throw new UnsupportedOperationException("not used");
        }
    }

    private static final class BlockingHandle implements ProcessRunner.Handle {

        @Override
        public void write(String stdin) {}

        @Override
        public String output() {
            return "";
        }

        @Override
        public Option<Integer> exitCode() {
            return Option.none();
        }

        @Override
        public void kill() {}

        @Override
        public void close() {}
    }
}
