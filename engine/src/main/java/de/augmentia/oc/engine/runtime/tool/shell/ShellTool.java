package de.augmentia.oc.engine.runtime.tool.shell;

import de.augmentia.oc.engine.core.contract.tool.ToolDefinition;
import de.augmentia.oc.engine.core.contract.tool.ToolOutcome;
import de.augmentia.oc.engine.core.contract.tool.ToolResult;
import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.json.Schema;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.ToolContent;
import de.augmentia.oc.engine.core.model.ToolName;
import de.augmentia.oc.engine.core.port.world.ProcessRunner;
import de.augmentia.oc.engine.core.shell.scan.PortableScanner;
import de.augmentia.oc.engine.core.shell.scan.ScanResult;
import de.augmentia.oc.engine.core.shell.scan.Scanner;
import de.augmentia.oc.engine.runtime.tool.JsonArgs;
import de.augmentia.oc.engine.runtime.tool.fs.ReadTool;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** {@code shell}: scan first (W-50), then run; output is truncated on demand. */
public final class ShellTool {

    public static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(2);
    public static final int MAX_OUTPUT_BYTES = 50 * 1024;

    private ShellTool() {}

    public static ToolDefinition definition(ProcessRunner processes, Path location, Scanner scanner) {
        if (processes == null || location == null) throw new IllegalArgumentException("dependencies must not be null");
        Scanner effective = scanner == null ? new PortableScanner() : scanner;
        Schema input = Schema.obj(Map.of(
                "command", Schema.str(),
                "workdir", Schema.opt(Schema.str()),
                "timeout", Schema.opt(Schema.lng()),
                "background", Schema.opt(Schema.bool())));
        return new ToolDefinition(new ToolName("shell"), "Run a shell command",
                input, Option.none(),
                (value, context) -> execute(processes, location, effective, value, context), null);
    }

    public static ScanResult scan(String command, Path cwd) {
        return new PortableScanner().scan(command, shellName(), cwd);
    }

    static ToolOutcome execute(ProcessRunner processes, Path location, Scanner scanner, JsonValue value,
                               de.augmentia.oc.engine.core.contract.tool.ToolContext context) {
        try {
            String command = JsonArgs.requiredString(value, "command");
            String workdir = JsonArgs.string(value, "workdir");
            Path cwd = workdir == null ? location : location.resolve(workdir).normalize();
            boolean background = JsonArgs.boolOr(value, "background", false);
            long timeoutMillis = JsonArgs.intOr(value, "timeout", -1);

            ScanResult scan = scanner.scan(command, shellName(), cwd);
            if (scan.commands().isEmpty()) {
                return ReadTool.failure("shell", "command cannot be analyzed");
            }

            Duration timeout = background ? Duration.ZERO
                    : timeoutMillis >= 0 ? Duration.ofMillis(timeoutMillis) : DEFAULT_TIMEOUT;
            ProcessRunner.ProcessSpec spec = spec(command, cwd, context.sessionId().value());

            if (background) {
                ProcessRunner.Handle handle = processes.start(spec);
                String text = "Command moved to the background. Shell ID: " + handle;
                return new ToolOutcome.Ok(new ToolResult(Option.none(),
                        List.of(new ToolContent.TextItem(text)),
                        Map.of("status", "running", "shellId", String.valueOf(handle.hashCode()))));
            }

            ProcessRunner.Exit exit = processes.run(spec, timeout);
            if (exit.timedOut()) {
                return ReadTool.failure("shell", "command timed out after " + timeout.toSeconds()
                        + "s; pass a larger timeout to continue");
            }
            if (exit.code() != 0) {
                return ReadTool.failure("shell", "command exited with " + exit.code() + ": " + exit.stderr());
            }
            String output = exit.stdout();
            boolean truncated = output.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_OUTPUT_BYTES;
            if (truncated) {
                output = output.substring(0, Math.min(output.length(), MAX_OUTPUT_BYTES));
            }
            Map<String, Object> metadata = truncated
                    ? Map.of("truncated", "yes", "exit", exit.code())
                    : Map.of("exit", exit.code());
            return new ToolOutcome.Ok(new ToolResult(Option.none(),
                    List.of(new ToolContent.TextItem(output)), metadata));
        } catch (IllegalArgumentException e) {
            return ReadTool.failure("shell", e.getMessage());
        }
    }

    private static ProcessRunner.ProcessSpec spec(String command, Path cwd, String sessionId) {
        List<String> invocation = new ArrayList<>();
        if (isWindows()) {
            invocation.add("cmd");
            invocation.add("/c");
        } else {
            invocation.add("sh");
            invocation.add("-c");
        }
        invocation.add(command);

        Map<String, String> env = Map.of(
                "AGENT", "1",
                "OPENCODE", "1",
                "AI_AGENT", "opencode",
                "OPENCODE_SESSION_ID", sessionId);
        return new ProcessRunner.ProcessSpec(invocation, Option.some(cwd), env, Option.none(), false);
    }

    private static String shellName() {
        return isWindows() ? "cmd" : "sh";
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
