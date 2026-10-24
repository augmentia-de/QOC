package de.augmentia.oc.engine.runtime.tool.meta;

import de.augmentia.oc.engine.core.contract.tool.ToolDefinition;
import de.augmentia.oc.engine.core.contract.tool.ToolOptions;
import de.augmentia.oc.engine.core.contract.tool.ToolOutcome;
import de.augmentia.oc.engine.core.contract.tool.ToolResult;
import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.json.Schema;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.SessionId;
import de.augmentia.oc.engine.core.model.ToolContent;
import de.augmentia.oc.engine.core.model.ToolName;
import de.augmentia.oc.engine.runtime.tool.fs.ReadTool;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code todowrite}: replaces the session's todo list. The list is kept in
 * memory for the life of the process; it is rendered from tool results.
 */
public final class TodoTool {

    public static final Set<String> STATUSES =
            Set.of("pending", "in_progress", "completed", "cancelled");

    public record Todo(String content, String status) {
        public Todo {
            if (content == null) content = "";
            if (status == null) status = "pending";
        }
    }

    private final Map<SessionId, List<Todo>> todos = new ConcurrentHashMap<>();

    public static ToolDefinition definition(TodoTool tool) {
        if (tool == null) throw new IllegalArgumentException("tool must not be null");
        Schema item = Schema.obj(Map.of(
                "content", Schema.str(),
                "status", Schema.enm(List.of("pending", "in_progress", "completed", "cancelled"))));
        Schema input = Schema.obj(Map.of("todos", Schema.arr(item)));
        return new ToolDefinition(new ToolName("todowrite"),
                "Replace the session todo list (pending, in_progress, completed, cancelled)",
                input, Option.none(),
                (value, context) -> tool.execute(context.sessionId(), value),
                new ToolOptions(Option.none(), Option.of("todowrite"), true, false));
    }

    public List<Todo> todos(SessionId session) {
        return List.copyOf(todos.getOrDefault(session, List.of()));
    }

    private ToolOutcome execute(SessionId session, JsonValue value) {
        try {
            JsonValue list = value instanceof JsonValue.Obj object
                    ? object.values().get("todos") : null;
            if (!(list instanceof JsonValue.Arr array)) {
                return ReadTool.failure("todowrite", "todos must be an array");
            }
            List<Todo> next = new ArrayList<>();
            for (JsonValue element : array.values()) {
                if (!(element instanceof JsonValue.Obj item)) continue;
                String content = string(item, "content");
                String status = string(item, "status");
                if (content == null || content.isBlank()) {
                    return ReadTool.failure("todowrite", "todo content must not be blank");
                }
                status = status == null || status.isBlank()
                        ? "pending" : status.toLowerCase(Locale.ROOT);
                if (!STATUSES.contains(status)) {
                    return ReadTool.failure("todowrite", "unknown todo status: " + status);
                }
                next.add(new Todo(content, status));
            }
            todos.put(session, List.copyOf(next));
            return new ToolOutcome.Ok(new ToolResult(Option.none(),
                    List.of(new ToolContent.TextItem(render(next))),
                    Map.of("count", next.size(),
                            "pending", next.stream().filter(todo -> !"completed".equals(todo.status())).count())));
        } catch (IllegalArgumentException e) {
            return ReadTool.failure("todowrite", e.getMessage());
        }
    }

    private static String render(List<Todo> todos) {
        if (todos.isEmpty()) return "No todos.";
        StringBuilder text = new StringBuilder();
        for (Todo todo : todos) {
            text.append("- [").append(todo.status()).append("] ").append(todo.content()).append('\n');
        }
        return text.toString().strip();
    }

    private static String string(JsonValue.Obj object, String key) {
        return object.values().get(key) instanceof JsonValue.Str str ? str.value() : null;
    }
}
