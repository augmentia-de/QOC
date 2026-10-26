package de.augmentia.oc.engine.runtime.tool.shell;

import de.augmentia.oc.engine.core.contract.tool.ToolDefinition;
import de.augmentia.oc.engine.core.contract.tool.ToolOutcome;
import de.augmentia.oc.engine.core.contract.tool.ToolResult;
import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.json.Schema;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.ToolContent;
import de.augmentia.oc.engine.core.model.ToolName;
import de.augmentia.oc.engine.runtime.tool.JsonArgs;
import de.augmentia.oc.engine.runtime.tool.fs.ReadTool;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code process}: inspect and control background shell processes owned by the
 * current session. Background shells never share state across sessions.
 */
public final class ProcessTool {

    public static final long DEFAULT_WAIT_MILLIS = 30_000;
    public static final long MAX_WAIT_MILLIS = 300_000;

    private ProcessTool() {}

    public static ToolDefinition definition(ProcessRegistry registry) {
        if (registry == null) throw new IllegalArgumentException("registry must not be null");
        Schema input = Schema.obj(Map.of(
                "action", Schema.enm(List.of("list", "status", "output", "wait", "kill")),
                "id", Schema.opt(Schema.str()),
                "timeout", Schema.opt(Schema.lng())));
        return new ToolDefinition(new ToolName("process"),
                "Inspect or control a background shell process",
                input, Option.none(),
                (value, context) -> execute(registry, value, context.sessionId()), null);
    }

    static ToolOutcome execute(ProcessRegistry registry, JsonValue value,
                               de.augmentia.oc.engine.core.model.SessionId session) {
        try {
            String action = JsonArgs.requiredString(value, "action");
            String id = JsonArgs.string(value, "id");
            return switch (action) {
                case "list" -> list(registry, session);
                case "status" -> require(registry, session, id, false);
                case "output" -> require(registry, session, id, true);
                case "wait" -> wait(registry, session, id, value);
                case "kill" -> kill(registry, session, id);
                default -> ReadTool.failure("process", "unknown action: " + action);
            };
        } catch (IllegalArgumentException e) {
            return ReadTool.failure("process", e.getMessage());
        } catch (RuntimeException e) {
            return ReadTool.failure("process", e.getMessage());
        }
    }

    private static ToolOutcome list(ProcessRegistry registry,
                                    de.augmentia.oc.engine.core.model.SessionId session) {
        List<ProcessRegistry.Snapshot> processes = registry.list(session);
        if (processes.isEmpty()) {
            return ok("No background shell processes.", Map.of("status", "empty"));
        }
        StringBuilder text = new StringBuilder();
        for (ProcessRegistry.Snapshot process : processes) {
            if (!text.isEmpty()) text.append('\n');
            text.append(process.id()).append(' ')
                    .append(process.state().name().toLowerCase())
                    .append(" exit=").append(process.exit().map(String::valueOf).orElse("-"))
                    .append(" command=").append(process.command());
        }
        return ok(text.toString(), Map.of("status", "ok", "count", processes.size()));
    }

    private static ToolOutcome require(ProcessRegistry registry,
                                       de.augmentia.oc.engine.core.model.SessionId session,
                                       String id, boolean output) {
        if (id == null || id.isBlank()) {
            return ReadTool.failure("process", "id is required for this action");
        }
        Option<ProcessRegistry.Snapshot> found = registry.get(session, id);
        if (found.isEmpty()) return ReadTool.failure("process", "unknown process: " + id);
        ProcessRegistry.Snapshot process = found.orElseThrow();
        String text = output ? renderOutput(process) : renderStatus(process);
        return ok(text, metadata(process));
    }

    private static ToolOutcome wait(ProcessRegistry registry,
                                    de.augmentia.oc.engine.core.model.SessionId session,
                                    String id, JsonValue value) {
        if (id == null || id.isBlank()) {
            return ReadTool.failure("process", "id is required for wait");
        }
        long configured = JsonArgs.longOr(value, "timeout", DEFAULT_WAIT_MILLIS);
        long millis = Math.max(0, Math.min(configured, MAX_WAIT_MILLIS));
        Option<ProcessRegistry.Snapshot> found = registry.await(session, id, Duration.ofMillis(millis));
        if (found.isEmpty()) return ReadTool.failure("process", "unknown process: " + id);
        ProcessRegistry.Snapshot process = found.orElseThrow();
        String text = process.running()
                ? "Still running after " + millis + " ms.\n" + renderStatus(process)
                : renderOutput(process);
        return ok(text, metadata(process));
    }

    private static ToolOutcome kill(ProcessRegistry registry,
                                    de.augmentia.oc.engine.core.model.SessionId session,
                                    String id) {
        if (id == null || id.isBlank()) {
            return ReadTool.failure("process", "id is required for kill");
        }
        boolean killed = registry.kill(session, id);
        Option<ProcessRegistry.Snapshot> found = registry.get(session, id);
        if (found.isEmpty()) return ReadTool.failure("process", "unknown process: " + id);
        ProcessRegistry.Snapshot process = found.orElseThrow();
        Map<String, Object> metadata = metadata(process);
        metadata.put("killed", killed);
        String text = killed ? "Process killed: " + id : "Process is not running: " + id;
        return ok(text, metadata);
    }

    private static String renderStatus(ProcessRegistry.Snapshot process) {
        StringBuilder text = new StringBuilder();
        text.append("id: ").append(process.id()).append('\n')
                .append("state: ").append(process.state().name().toLowerCase()).append('\n')
                .append("command: ").append(process.command()).append('\n')
                .append("exit: ").append(process.exit().map(String::valueOf).orElse("-"));
        if (!process.stderr().isBlank()) {
            text.append("\n[stderr]\n").append(process.stderr());
        }
        return text.toString();
    }

    private static String renderOutput(ProcessRegistry.Snapshot process) {
        StringBuilder text = new StringBuilder();
        if (!process.stdout().isBlank()) text.append(process.stdout());
        if (!process.stderr().isBlank()) {
            if (!text.isEmpty() && !text.toString().endsWith("\n")) text.append('\n');
            text.append("[stderr]\n").append(process.stderr());
        }
        if (text.isEmpty()) text.append("(no output)");
        return text.toString();
    }

    private static Map<String, Object> metadata(ProcessRegistry.Snapshot process) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("processId", process.id());
        metadata.put("status", process.state().name().toLowerCase());
        process.exit().ifPresent(exit -> metadata.put("exit", exit));
        return metadata;
    }

    private static ToolOutcome ok(String text, Map<String, Object> metadata) {
        return new ToolOutcome.Ok(new ToolResult(Option.none(),
                List.of(new ToolContent.TextItem(text)), metadata));
    }
}
