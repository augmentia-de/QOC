package de.augmentia.oc.engine.runtime;

import de.augmentia.oc.engine.core.contract.tool.ToolCall;
import de.augmentia.oc.engine.core.contract.tool.ToolContext;
import de.augmentia.oc.engine.core.contract.tool.ToolOutcome;
import de.augmentia.oc.engine.core.contract.tool.ToolSnapshot;
import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.model.AgentId;
import de.augmentia.oc.engine.core.model.CallId;
import de.augmentia.oc.engine.core.model.MessageId;
import de.augmentia.oc.engine.core.model.SessionId;
import de.augmentia.oc.engine.core.model.ToolName;
import de.augmentia.oc.engine.core.port.world.ProcessRunner;
import de.augmentia.oc.engine.core.contract.permission.PermissionGate;
import de.augmentia.oc.engine.core.shell.scan.ScanResult;
import de.augmentia.oc.engine.runtime.tool.runtime.ToolAuthorizer;
import de.augmentia.oc.engine.runtime.tool.runtime.ToolRuntime;
import de.augmentia.oc.engine.runtime.tool.shell.ProcessRegistry;
import de.augmentia.oc.engine.runtime.tool.shell.ShellTool;
import de.augmentia.oc.engine.testing.FakeProcess;
import de.augmentia.oc.engine.testing.InMemoryToolOutput;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

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
    void successfulCommandKeepsStderrVisible() {
        FakeProcess process = new FakeProcess(new ProcessRunner.Exit(0, "hello", "warning", false));

        ToolOutcome outcome = execute(process, args("echo hello", null, null, false));

        assertThat(text(outcome)).contains("[stdout]").contains("hello")
                .contains("[stderr]").contains("warning");
    }

    @Test
    void nonZeroExitExposesStdoutAndStderr() {
        FakeProcess process = new FakeProcess(new ProcessRunner.Exit(2, "partial", "bad", false));

        ToolOutcome outcome = execute(process, args("false", null, null, false));

        assertThat(outcome).isInstanceOf(ToolOutcome.Failed.class);
        assertThat(((ToolOutcome.Failed) outcome).error().message())
                .contains("command exited with 2")
                .contains("[stdout]").contains("partial")
                .contains("[stderr]").contains("bad");
        assertThat(((ToolOutcome.Failed) outcome).metadata()).containsEntry("exit", 2);
    }

    @Test
    void toolDoesNotPreTruncateLargeOutput() {
        String large = "x".repeat(60 * 1024);
        FakeProcess process = new FakeProcess(new ProcessRunner.Exit(0, large, "", false));

        ToolOutcome outcome = execute(process, args("generate", null, null, false));

        assertThat(text(outcome)).hasSize(large.length());
        assertThat(((ToolOutcome.Ok) outcome).result().metadata()).doesNotContainKey("truncated");
    }

    @Test
    void toolRuntimeStoresTheFullLargeShellOutput() {
        String large = "line\n".repeat(2_500);
        FakeProcess process = new FakeProcess(new ProcessRunner.Exit(0, large, "", false));
        InMemoryToolOutput store = new InMemoryToolOutput();
        var definition = ShellTool.definition(process, ROOT, null);
        ToolRuntime runtime = new ToolRuntime(
                new ToolSnapshot(Map.of(new ToolName("shell"), definition)),
                ToolAuthorizer.defaultAuthorizer(), request -> {}, noopHooks(), store);
        SessionId session = SessionId.create();
        ToolOutcome outcome = runtime.execute(
                new ToolCall(CallId.create(), new ToolName("shell"),
                        args("large", null, null, false)),
                new ToolContext(session, AgentId.create(), MessageId.create(), CallId.create()));

        assertThat(((ToolOutcome.Ok) outcome).result().metadata())
                .containsEntry("truncated", "yes")
                .containsEntry("exit", 0);
        assertThat(store.size()).isEqualTo(1);
        var parsed = de.augmentia.oc.engine.core.model.ArtifactRef.parse(String.valueOf(
                ((ToolOutcome.Ok) outcome).result().metadata().get("outputPath")));
        assertThat(parsed.session()).isEqualTo(session);
        assertThat(store.text(parsed.session(), parsed.id())).contains(large);
    }

    @Test
    void backgroundProcessHasAStableIdAndCanBeInspected() {
        ProcessRegistry registry = new ProcessRegistry();
        FakeProcess process = new FakeProcess(new ProcessRunner.Exit(0, "done", "", false));
        SessionId session = SessionId.create();
        ToolContext context = new ToolContext(session, AgentId.create(), MessageId.create(), CallId.create());

        ToolOutcome outcome = ShellTool.definition(process, ROOT, ROOT, null, null, registry)
                .execute().execute(args("sleep 1", null, null, true), context);
        String shellId = String.valueOf(((ToolOutcome.Ok) outcome).result().metadata().get("shellId"));

        assertThat(shellId).startsWith("sh_");
        assertThat(registry.get(session, shellId).isPresent()).isTrue();
        await(shellId, registry, session);
        ProcessRegistry.Snapshot snapshot = registry.get(session, shellId).orElseThrow();
        assertThat(snapshot.state()).isEqualTo(ProcessRegistry.State.COMPLETED);
        assertThat(snapshot.stdout()).isEqualTo("done");
        assertThat(snapshot.exit().orElseThrow()).isZero();
        registry.closeAll();
    }

    @Test
    void backgroundProcessIsScopedToItsSession() {
        ProcessRegistry registry = new ProcessRegistry();
        FakeProcess process = new FakeProcess(new ProcessRunner.Exit(0, "", "", false));
        SessionId owner = SessionId.create();
        SessionId other = SessionId.create();
        ToolContext context = new ToolContext(owner, AgentId.create(), MessageId.create(), CallId.create());

        ToolOutcome outcome = ShellTool.definition(process, ROOT, ROOT, null, null, registry)
                .execute().execute(args("sleep 1", null, null, true), context);
        String shellId = String.valueOf(((ToolOutcome.Ok) outcome).result().metadata().get("shellId"));

        assertThat(registry.get(other, shellId).isEmpty()).isTrue();
        assertThat(registry.list(other)).isEmpty();
        registry.closeAll();
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

    private static de.augmentia.oc.engine.core.contract.tool.Hooks noopHooks() {
        return new de.augmentia.oc.engine.core.contract.tool.Hooks() {
            @Override
            public ToolCall beforeToolCall(ToolContext context, ToolCall call) {
                return call;
            }

            @Override
            public de.augmentia.oc.engine.core.contract.tool.ToolResult afterToolCall(
                    ToolContext context, de.augmentia.oc.engine.core.contract.tool.ToolResult result) {
                return result;
            }
        };
    }

    private static void await(String shellId, ProcessRegistry registry, SessionId session) {
        long deadline = System.currentTimeMillis() + 2_000;
        while (registry.get(session, shellId).orElseThrow().running()
                && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
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
