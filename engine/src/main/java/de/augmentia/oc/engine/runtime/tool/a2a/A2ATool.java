package de.augmentia.oc.engine.runtime.tool.a2a;

import de.augmentia.oc.engine.core.contract.tool.ToolDefinition;
import de.augmentia.oc.engine.core.contract.tool.ToolOptions;
import de.augmentia.oc.engine.core.contract.tool.ToolOutcome;
import de.augmentia.oc.engine.core.contract.tool.ToolResult;
import de.augmentia.oc.engine.core.json.Json;
import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.json.Schema;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.SessionError;
import de.augmentia.oc.engine.core.model.ToolContent;
import de.augmentia.oc.engine.core.model.ToolName;
import org.a2aproject.sdk.A2A;
import org.a2aproject.sdk.client.Client;
import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.client.MessageEvent;
import org.a2aproject.sdk.client.TaskEvent;
import org.a2aproject.sdk.client.TaskUpdateEvent;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.Artifact;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TextPart;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Exposes a remote A2A agent as a QOC tool.
 *
 * <p>The tool blocks until the remote A2A agent has produced a final
 * {@link Message} or {@link Task}. The remote text parts are concatenated into
 * the tool result so the model can consume them like any other tool output.</p>
 */
public final class A2ATool {
    private static final Logger LOG = Logger.getLogger(A2ATool.class.getName());

    private A2ATool() {
    }

    public static ToolDefinition definition(AgentCard card, Client client, String toolName) {
        String name = toolName == null || toolName.isBlank()
                ? "a2a_" + normalize(card.name())
                : normalize(toolName);
        String description = card.description() == null
                ? "Call A2A agent " + card.name()
                : card.description();
        Schema schema = Schema.object(Map.of(
                "message", Schema.object(Map.of("text", Schema.string()), false),
                "text", Schema.string()
        ), false);
        return new ToolDefinition(new ToolName(name), description, schema, Option.none(),
                (input, context) -> execute(card, client, input), ToolOptions.defaults());
    }

    private static ToolOutcome execute(AgentCard card, Client client, JsonValue input) {
        try {
            String text = extractText(input);
            if (text == null || text.isBlank()) {
                text = Json.encode(input);
            }
            Message message = A2A.toUserMessage(text);
            List<String> results = new ArrayList<>();
            Throwable[] error = new Throwable[1];
            client.sendMessage(message,
                    List.of((event, agentCard) -> collect(event, results)),
                    throwable -> error[0] = throwable,
                    null);
            if (error[0] != null) {
                return failure("a2a_error", messageOf(error[0]));
            }
            String result = results.isEmpty() ? "OK" : String.join("\n", results);
            return new ToolOutcome.Ok(new ToolResult(Option.none(),
                    List.of(new ToolContent.TextItem(result)),
                    Map.of("agent", card.name())));
        } catch (Exception e) {
            LOG.warning("A2A tool failed: " + e.getMessage());
            return failure("a2a_error", messageOf(e));
        }
    }

    private static void collect(ClientEvent event, List<String> results) {
        if (event instanceof MessageEvent messageEvent) {
            addMessage(messageEvent.getMessage(), results);
        } else if (event instanceof TaskEvent taskEvent) {
            addTask(taskEvent.getTask(), results);
        } else if (event instanceof TaskUpdateEvent updateEvent) {
            addTask(updateEvent.getTask(), results);
        }
    }

    private static void addTask(Task task, List<String> results) {
        if (task == null) {
            return;
        }
        if (task.status() != null) {
            addMessage(task.status().message(), results);
        }
        if (task.artifacts() != null) {
            for (Artifact artifact : task.artifacts()) {
                addParts(artifact.parts(), results);
            }
        }
    }

    private static void addMessage(Message message, List<String> results) {
        if (message == null) {
            return;
        }
        addParts(message.parts(), results);
    }

    private static void addParts(List<Part<?>> parts, List<String> results) {
        if (parts == null) {
            return;
        }
        for (Part<?> part : parts) {
            if (part instanceof TextPart textPart && textPart.text() != null && !textPart.text().isBlank()) {
                results.add(textPart.text());
            }
        }
    }

    private static String extractText(JsonValue input) {
        if (!(input instanceof JsonValue.Obj obj)) {
            return null;
        }
        if (obj.values().get("text") instanceof JsonValue.Str s) {
            return s.value();
        }
        if (obj.values().get("message") instanceof JsonValue.Obj m) {
            if (m.values().get("text") instanceof JsonValue.Str s) {
                return s.value();
            }
        }
        return null;
    }

    private static String messageOf(Throwable throwable) {
        if (throwable.getMessage() != null && !throwable.getMessage().isBlank()) {
            return throwable.getMessage();
        }
        return throwable.getClass().getSimpleName();
    }

    private static String normalize(String value) {
        if (value == null) return "agent";
        return value.replaceAll("[^A-Za-z0-9_-]", "_").toLowerCase();
    }

    private static ToolOutcome failure(String type, String message) {
        return new ToolOutcome.Failed(new SessionError.ToolExecutionError(type, message));
    }
}
