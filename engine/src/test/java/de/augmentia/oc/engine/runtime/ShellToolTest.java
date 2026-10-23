package de.augmentia.oc.engine.runtime;

import de.augmentia.oc.engine.core.contract.tool.ToolContext;
import de.augmentia.oc.engine.core.contract.tool.ToolOutcome;
import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.model.AgentId;
import de.augmentia.oc.engine.core.model.CallId;
import de.augmentia.oc.engine.core.model.MessageId;
import de.augmentia.oc.engine.core.model.SessionId;
import de.augmentia.oc.engine.core.port.world.ProcessRunner;
import de.augmentia.oc.engine.core.contract.permission.PermissionGate;
import de.augmentia.oc.engine.core.shell.scan.ScanResult;
import de.augmentia.oc.engine.runtime.tool.shell.ShellTool;
import de.augmentia.oc.engine.testing.FakeProcess;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ShellToolTest {

    private static final Path ROOT = Path.of("/work").toAbsolutePath();

    @Test
    void scanSplitsChainedCommands() {
        ScanResult scan = ShellTool.scan("git status && rm -rf build", ROOT);

        assertThat(scan.commands()).hasSize(2);
        assertThat(scan.commands().get(0).resource()).isEqualTo("git status");
        assertThat(scan.commands().get(0).save()).isEqualTo("git status *");
        assertThat(scan.commands().get(1).resource()).isEqualTo("rm -rf build");
    }

    @Test
    void successfulCommandReturnsOutputAndExit() {
        FakeProcess process = new FakeProcess(new ProcessRunner.Exit(0, "hello", "", false));
        ToolOutcome outcome = execute(process, args("echo hello", null, null, false));

        assertThat(outcome).isInstanceOf(ToolOutcome.Ok.class);
        assertThat(text(outcome)).isEqualTo("hello");
        assertThat(((ToolOutcome.Ok) outcome).result().metadata()).containsEntry("exit", 0);
    }

    @Test
    void nonZeroExitIsAToolFailure() {
        FakeProcess process = new FakeProcess(new ProcessRunner.Exit(2, "", "bad", false));
        assertThat(execute(process, args("false", null, null, false)))
                .isInstanceOf(ToolOutcome.Failed.class);
    }

    @Test
    void externalWorkdirConsultsPermissionGate() {
        FakeProcess process = new FakeProcess(new ProcessRunner.Exit(0, "ok", "", false));
        ToolContext context = new ToolContext(SessionId.create(), AgentId.create(),
                MessageId.create(), CallId.create());
        PermissionGate allow = request -> { };
        ShellTool.definition(process, ROOT, ROOT, null, allow).execute().execute(
                args("echo hi", "/tmp", null, false), context);
    }

    @Test
    void timeoutIsAToolFailureWithAHint() {
        FakeProcess process = new FakeProcess(new ProcessRunner.Exit(124, "", "", true));
        ToolOutcome outcome = execute(process, args("sleep 10", null, 10L, false));

        assertThat(outcome).isInstanceOf(ToolOutcome.Failed.class);
        assertThat(((ToolOutcome.Failed) outcome).error().message()).contains("timed out");
    }

    @Test
    void backgroundReturnsImmediately() {
        FakeProcess process = new FakeProcess(new ProcessRunner.Exit(0, "", "", false));
        ToolOutcome outcome = execute(process, args("sleep 60", null, null, true));

        assertThat(outcome).isInstanceOf(ToolOutcome.Ok.class);
        assertThat(((ToolOutcome.Ok) outcome).result().metadata()).containsEntry("status", "running");
        assertThat(process.handles()).hasSize(1);
    }

    @Test
    void standardEnvironmentIsSet() {
        FakeProcess process = new FakeProcess(new ProcessRunner.Exit(0, "", "", false));
        String session = SessionId.create().value();
        ToolContext context = new ToolContext(SessionId.parse(session), AgentId.create(),
                MessageId.create(), CallId.create());

        ShellTool.definition(process, ROOT, null).execute().execute(args("ls", null, null, false), context);

        assertThat(process.calls().getFirst().env())
                .containsEntry("AGENT", "1")
                .containsEntry("OPENCODE", "1")
                .containsEntry("OPENCODE_SESSION_ID", session);
    }

    @Test
    void unanalyzableCommandIsATechnicalFailure() {
        FakeProcess process = new FakeProcess();
        assertThat(execute(process, args("   ", null, null, false)))
                .isInstanceOf(ToolOutcome.Failed.class);
    }

    private static ToolOutcome execute(FakeProcess process, JsonValue args) {
        ToolContext context = new ToolContext(SessionId.create(), AgentId.create(),
                MessageId.create(), CallId.create());
        return ShellTool.definition(process, ROOT, null).execute().execute(args, context);
    }

    private static JsonValue args(String command, String workdir, Long timeout, boolean background) {
        var values = new java.util.LinkedHashMap<String, JsonValue>();
        values.put("command", JsonValue.of(command));
        if (workdir != null) values.put("workdir", JsonValue.of(workdir));
        if (timeout != null) values.put("timeout", JsonValue.of(timeout));
        values.put("background", JsonValue.of(background));
        return JsonValue.of(values);
    }

    private static String text(ToolOutcome outcome) {
        return ((de.augmentia.oc.engine.core.model.ToolContent.TextItem)
                ((ToolOutcome.Ok) outcome).result().content().getFirst()).text();
    }
}
