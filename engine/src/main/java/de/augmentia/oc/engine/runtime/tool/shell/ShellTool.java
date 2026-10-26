package de.augmentia.oc.engine.runtime.tool.shell;

import de.augmentia.oc.engine.core.contract.permission.PermissionGate;
import de.augmentia.oc.engine.core.contract.permission.PermissionRequest;
import de.augmentia.oc.engine.core.contract.tool.ToolDefinition;
import de.augmentia.oc.engine.core.contract.tool.ToolOutcome;
import de.augmentia.oc.engine.core.contract.tool.ToolResult;
import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.json.Schema;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.PermissionRequestId;
import de.augmentia.oc.engine.core.model.SessionError;
import de.augmentia.oc.engine.core.model.SessionId;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** {@code shell}: scan first (W-50), then check external_directory, then run. */
public final class ShellTool {

    public static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(2);

    private ShellTool() {}

    public static ToolDefinition definition(ProcessRunner processes, Path location, Scanner scanner) {
        return definition(processes, location, location, scanner, null, new ProcessRegistry());
    }

    public static ToolDefinition definition(ProcessRunner processes, Path location, Path projectRoot,
                                           Scanner scanner, PermissionGate permissionGate) {
        return definition(processes, location, projectRoot, scanner, permissionGate, new ProcessRegistry());
    }

    public static ToolDefinition definition(ProcessRunner processes, Path location, Path projectRoot,
                                           Scanner scanner, PermissionGate permissionGate,
                                           ProcessRegistry registry) {
        if (processes == null || location == null) throw new IllegalArgumentException("dependencies must not be null");
        if (registry == null) throw new IllegalArgumentException("process registry must not be null");
        Path root = projectRoot == null ? location : projectRoot;
        Scanner effective = scanner == null ? new PortableScanner() : scanner;
        Schema input = Schema.obj(Map.of(
                "command", Schema.str(),
                "workdir", Schema.opt(Schema.str()),
                "timeout", Schema.opt(Schema.lng()),
                "background", Schema.opt(Schema.bool()),
                "pty", Schema.opt(Schema.bool())));
        return new ToolDefinition(new ToolName("shell"), "Run a shell command",
                input, Option.none(),
                (value, context) -> execute(processes, location, root, effective, value, context,
                        permissionGate, registry), null);
    }

    public static ScanResult scan(String command, Path cwd) {
        return new PortableScanner().scan(command, shellName(), cwd);
    }

    static ToolOutcome execute(ProcessRunner processes, Path location, Path projectRoot, Scanner scanner,
                               JsonValue value, de.augmentia.oc.engine.core.contract.tool.ToolContext context,
                               PermissionGate permissionGate, ProcessRegistry registry) {
        try {
            String command = JsonArgs.requiredString(value, "command");
            String workdir = JsonArgs.string(value, "workdir");
            Path cwd = workdir == null ? location : location.resolve(workdir).normalize();
            boolean background = JsonArgs.boolOr(value, "background", false);
            boolean pty = JsonArgs.boolOr(value, "pty", false);
            long timeoutMillis = JsonArgs.intOr(value, "timeout", -1);

            ScanResult scan = scanner.scan(command, shellName(), cwd);
            if (scan.commands().isEmpty()) {
                return ReadTool.failure("shell", "command cannot be analyzed");
            }

            // External-directory check: any scanned directory outside the
            // location/project root requires an explicit permission decision.
            if (permissionGate != null) {
                for (Path dir : scan.directories()) {
                    Path normalized = dir.toAbsolutePath().normalize();
                    boolean internal = normalized.startsWith(location.toAbsolutePath().normalize())
                            || normalized.startsWith(projectRoot.toAbsolutePath().normalize());
                    if (!internal) {
                        PermissionRequest request = new PermissionRequest(
                                PermissionRequestId.create(),
                                context.sessionId(),
                                "external_directory",
                                List.of(normalized.toString()),
                                List.of(normalized.toString() + "/*"),
                                Map.of("tool", "shell", "command", command),
                                null,
                                null);
                        permissionGate.assertAllowed(request);
                    }
                }
            }

            Duration timeout = background ? Duration.ZERO
                    : timeoutMillis >= 0 ? Duration.ofMillis(timeoutMillis) : DEFAULT_TIMEOUT;
            ProcessRunner.ProcessSpec spec = spec(command, cwd, context.sessionId().value(), pty);

            if (background) {
                ProcessRunner.Handle handle = processes.start(spec);
                String shellId = registry.register(context.sessionId(), command, handle);
                String text = "Command moved to the background. Shell ID: " + shellId
                        + ". Use process(action=status|output|wait|kill, id=" + shellId + ") to inspect it.";
                return new ToolOutcome.Ok(new ToolResult(Option.none(),
                        List.of(new ToolContent.TextItem(text)),
                        Map.of("status", "running", "shellId", shellId, "background", true)));
            }

            ProcessRunner.Exit exit = processes.run(spec, timeout);
            if (exit.timedOut()) {
                String message = "command timed out after " + timeout.toSeconds()
                        + "s; pass a larger timeout to continue";
                return failure(withOutput(message, exit), exit);
            }
            if (exit.code() != 0) {
                return failure(withOutput("command exited with " + exit.code(), exit), exit);
            }
            // The tool returns the complete streams. ToolRuntime/OutputBounding is
            // the single place that truncates and spills the full text.
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("exit", exit.code());
            metadata.put("status", "completed");
            return new ToolOutcome.Ok(new ToolResult(Option.none(),
                    List.of(new ToolContent.TextItem(combined(exit))), metadata));
        } catch (IllegalArgumentException e) {
            return ReadTool.failure("shell", e.getMessage());
        } catch (de.augmentia.oc.engine.core.contract.permission.PermissionDenied e) {
            return ReadTool.failure("shell", "permission denied: " + e.getMessage());
        } catch (de.augmentia.oc.engine.core.error.Aborted e) {
            return ReadTool.failure("shell", "aborted: " + e.getMessage());
        } catch (java.io.UncheckedIOException e) {
            return ReadTool.failure("shell", "unable to execute command: " + e.getMessage());
        }
    }

    /**
     * Visible text for both streams. Markers appear only when a channel is
     * non-empty, so the common stdout-only case stays byte-identical.
     */
    static String combined(ProcessRunner.Exit exit) {
        String stdout = exit.stdout();
        String stderr = exit.stderr();
        if (stderr.isBlank()) return stdout;
        StringBuilder text = new StringBuilder();
        if (!stdout.isBlank()) {
            text.append("[stdout]\n").append(stdout);
            if (!stdout.endsWith("\n")) text.append('\n');
        }
        text.append("[stderr]\n").append(stderr);
        return text.toString();
    }

    private static String withOutput(String message, ProcessRunner.Exit exit) {
        String output = combined(exit);
        return output.isBlank() ? message : message + "\n" + output;
    }

    private static ToolOutcome failure(String message, ProcessRunner.Exit exit) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("exit", exit.code());
        metadata.put("status", exit.timedOut() ? "timed_out" : "failed");
        return new ToolOutcome.Failed(
                new SessionError.ToolExecutionError("shell", message), metadata);
    }

    private static ProcessRunner.ProcessSpec spec(String command, Path cwd, String sessionId, boolean pty) {
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
        return new ProcessRunner.ProcessSpec(invocation, Option.some(cwd), env, Option.none(), pty);
    }

    private static String shellName() {
        return isWindows() ? "cmd" : "sh";
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}