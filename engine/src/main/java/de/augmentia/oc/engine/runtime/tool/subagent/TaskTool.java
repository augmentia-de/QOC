package de.augmentia.oc.engine.runtime.tool.subagent;

import de.augmentia.oc.engine.core.contract.tool.ToolDefinition;
import de.augmentia.oc.engine.core.contract.tool.ToolOutcome;
import de.augmentia.oc.engine.core.contract.tool.ToolResult;
import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.json.Schema;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.SessionId;
import de.augmentia.oc.engine.core.model.ToolContent;
import de.augmentia.oc.engine.core.model.ToolName;
import de.augmentia.oc.engine.runtime.tool.JsonArgs;
import de.augmentia.oc.engine.runtime.tool.fs.ReadTool;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code task}: inspect and control background subagent tasks owned by the
 * current session. This is the non-blocking way for a parent to learn whether
 * a background child succeeded, failed or was cancelled.
 */
public final class TaskTool {

    public static final long DEFAULT_WAIT_MILLIS = 30_000;
    public static final long MAX_WAIT_MILLIS = 300_000;

    private TaskTool() {}

    public static ToolDefinition definition(SubagentTool subagent) {
        if (subagent == null) throw new IllegalArgumentException("subagent must not be null");
        Schema input = Schema.obj(Map.of(
                "action", Schema.enm(List.of("list", "status", "wait", "cancel")),
                "id", Schema.opt(Schema.str()),
                "timeout", Schema.opt(Schema.lng())));
        return new ToolDefinition(new ToolName("task"),
                "Inspect or control a background subagent task",
                input, Option.none(),
                (value, context) -> execute(subagent.backgroundTasks(), value, context.sessionId()), null);
    }

    static ToolOutcome execute(BackgroundTasks tasks, JsonValue value, SessionId session) {
        try {
            String action = JsonArgs.requiredString(value, "action");
            String id = JsonArgs.string(value, "id");
            return switch (action) {
                case "list" -> list(tasks, session);
                case "status" -> status(tasks, session, id);
                case "wait" -> wait(tasks, session, id, value);
                case "cancel" -> cancel(tasks, session, id);
                default -> ReadTool.failure("task", "unknown action: " + action);
            };
        } catch (IllegalArgumentException e) {
            return ReadTool.failure("task", e.getMessage());
        } catch (RuntimeException e) {
            return ReadTool.failure("task", e.getMessage());
        }
    }

    private static ToolOutcome list(BackgroundTasks tasks, SessionId session) {
        List<BackgroundTasks.Snapshot> all = tasks.list(session);
        if (all.isEmpty()) {
            return ok("No background subagent tasks.", Map.of("status", "empty"));
        }
        StringBuilder text = new StringBuilder();
        for (BackgroundTasks.Snapshot task : all) {
            if (!text.isEmpty()) text.append('\n');
            text.append(task.id()).append(' ')
                    .append(task.state().name().toLowerCase())
                    .append(" agent=").append(task.agent());
            if (task.childSession().isPresent()) {
                text.append(" session=").append(task.childSession().orElseThrow().value());
            }
        }
        return ok(text.toString(), Map.of("status", "ok", "count", all.size()));
    }

    private static ToolOutcome status(BackgroundTasks tasks, SessionId session, String id) {
        if (id == null || id.isBlank()) return ReadTool.failure("task", "id is required for status");
        Option<BackgroundTasks.Snapshot> found = tasks.get(session, id);
        if (found.isEmpty()) return ReadTool.failure("task", "unknown task: " + id);
        return ok(render(found.orElseThrow()), metadata(found.orElseThrow()));
    }

    private static ToolOutcome wait(BackgroundTasks tasks, SessionId session, String id, JsonValue value) {
        if (id == null || id.isBlank()) return ReadTool.failure("task", "id is required for wait");
        long configured = JsonArgs.longOr(value, "timeout", DEFAULT_WAIT_MILLIS);
        long millis = Math.max(0, Math.min(configured, MAX_WAIT_MILLIS));
        Option<BackgroundTasks.Snapshot> found = tasks.await(session, id, Duration.ofMillis(millis));
        if (found.isEmpty()) return ReadTool.failure("task", "unknown task: " + id);
        BackgroundTasks.Snapshot task = found.orElseThrow();
        String text = task.running()
                ? "Still running after " + millis + " ms.\n" + render(task)
                : render(task);
        return ok(text, metadata(task));
    }

    private static ToolOutcome cancel(BackgroundTasks tasks, SessionId session, String id) {
        if (id == null || id.isBlank()) return ReadTool.failure("task", "id is required for cancel");
        boolean cancelled = tasks.cancel(session, id);
        Option<BackgroundTasks.Snapshot> found = tasks.get(session, id);
        if (found.isEmpty()) return ReadTool.failure("task", "unknown task: " + id);
        BackgroundTasks.Snapshot task = found.orElseThrow();
        Map<String, Object> metadata = metadata(task);
        metadata.put("cancelled", cancelled);
        return ok(cancelled ? "Task cancelled: " + id : "Task is not running: " + id, metadata);
    }

    private static String render(BackgroundTasks.Snapshot task) {
        StringBuilder text = new StringBuilder();
        text.append("id: ").append(task.id()).append('\n')
                .append("state: ").append(task.state().name().toLowerCase()).append('\n')
                .append("agent: ").append(task.agent()).append('\n')
                .append("tokens: ").append(task.usage().total());
        if (task.childSession().isPresent()) {
            text.append("\nchildSession: ").append(task.childSession().orElseThrow().value());
        }
        if (!task.error().isBlank()) {
            text.append("\nerror: ").append(task.error());
        }
        if (!task.text().isBlank()) {
            text.append("\nresult:\n").append(task.text());
        }
        return text.toString();
    }

    private static Map<String, Object> metadata(BackgroundTasks.Snapshot task) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("taskId", task.id());
        metadata.put("status", task.state().name().toLowerCase());
        metadata.put("agent", task.agent());
        task.childSession().ifPresent(child -> metadata.put("childSession", child.value()));
        return metadata;
    }

    private static ToolOutcome ok(String text, Map<String, Object> metadata) {
        return new ToolOutcome.Ok(new ToolResult(Option.none(),
                List.of(new ToolContent.TextItem(text)), metadata));
    }
}
