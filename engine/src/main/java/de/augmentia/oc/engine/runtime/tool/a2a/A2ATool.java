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
import org.a2aproject.sdk.client.transport.spi.interceptors.ClientCallContext;
import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.client.MessageEvent;
import org.a2aproject.sdk.client.TaskEvent;
import org.a2aproject.sdk.client.TaskUpdateEvent;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.Artifact;
import org.a2aproject.sdk.spec.DataPart;
import org.a2aproject.sdk.spec.FilePart;
import org.a2aproject.sdk.spec.FileWithUri;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TextPart;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Exposes a remote A2A agent as a QOC tool.
 *
 * <p>The tool blocks until the remote A2A agent has produced a final
 * {@link Message} or {@link Task}. Remote text, data and file parts are mapped
 * into the tool result so the model can consume them like any other tool output.</p>
 */
public final class A2ATool {
    private static final Logger LOG = Logger.getLogger(A2ATool.class.getName());

    private A2ATool() {
    }

    public static ToolDefinition definition(AgentCard card, Client client, String toolName) {
        return definition(card, client, toolName, Map.of());
    }

    public static ToolDefinition definition(AgentCard card, Client client, String toolName,
                                           Map<String, String> headers) {
        Map<String, String> h = headers == null ? Map.of() : headers;
        String name = toolName == null || toolName.isBlank()
                ? "a2a_" + normalize(card.name())
                : normalize(toolName);
        String description = card.description() == null
                ? "Call A2A agent " + card.name()
                : card.description();
        if (card.skills() != null && !card.skills().isEmpty()) {
            StringBuilder sb = new StringBuilder(description);
            sb.append(" Skills: ");
            for (int i = 0; i < card.skills().size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(card.skills().get(i).name());
            }
            sb.append('.');
            description = sb.toString();
        }
        Schema schema = Schema.object(Map.of(
                "message", Schema.object(Map.of("text", Schema.string()), false),
                "text", Schema.string(),
                "skill", Schema.string()
        ), false);
        Map<String, String> finalHeaders = h;
        return new ToolDefinition(new ToolName(name), description, schema, Option.none(),
                (input, context) -> execute(card, client, input, finalHeaders), ToolOptions.defaults());
    }

    private static ToolOutcome execute(AgentCard card, Client client, JsonValue input, Map<String, String> headers) {
        try {
            String text = extractText(input);
            String skill = extractSkill(input);
            if (text == null || text.isBlank()) {
                text = Json.encode(input);
            }
            if (skill != null && !skill.isBlank()) {
                text = "[" + skill + "] " + text;
            }
            Message message = A2A.toUserMessage(text);
            List<Object> results = new ArrayList<>();
            Throwable[] error = new Throwable[1];
            ClientCallContext context = headers == null || headers.isEmpty()
                    ? null
                    : new ClientCallContext(Map.of(), headers);
            client.sendMessage(message,
                    List.of((java.util.function.BiConsumer<ClientEvent, AgentCard>) (event, agentCard) -> collect(event, results)),
                    throwable -> error[0] = throwable,
                    context);
            if (error[0] != null) {
                return failure("a2a_error", messageOf(error[0]));
            }
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("agent", card.name());
            if (skill != null && !skill.isBlank()) meta.put("skill", skill);
            return new ToolOutcome.Ok(new ToolResult(Option.none(), toContent(results), meta));
        } catch (Exception e) {
            LOG.warning("A2A tool failed: " + e.getMessage());
            return failure("a2a_error", messageOf(e));
        }
    }

    private static void collect(ClientEvent event, List<Object> results) {
        if (event instanceof MessageEvent messageEvent) {
            addMessage(messageEvent.getMessage(), results);
        } else if (event instanceof TaskEvent taskEvent) {
            addTask(taskEvent.getTask(), results);
        } else if (event instanceof TaskUpdateEvent updateEvent) {
            addTask(updateEvent.getTask(), results);
        }
    }

    private static void addTask(Task task, List<Object> results) {
        if (task == null) return;
        if (task.status() != null) addMessage(task.status().message(), results);
        if (task.artifacts() != null) {
            for (Artifact artifact : task.artifacts()) addParts(artifact.parts(), results);
        }
    }

    private static void addMessage(Message message, List<Object> results) {
        if (message == null) return;
        addParts(message.parts(), results);
    }

    private static void addParts(List<Part<?>> parts, List<Object> results) {
        if (parts == null) return;
        for (Part<?> part : parts) {
            if (part instanceof TextPart tp && tp.text() != null && !tp.text().isBlank()) {
                results.add(tp.text());
            } else if (part instanceof DataPart dp) {
                results.add(dp.data());
            } else if (part instanceof FilePart fp && fp.file() != null) {
                String info = "file:" + fp.file().name() + " (" + fp.file().mimeType() + ")";
                if (fp.file() instanceof FileWithUri fwu) info += " @ " + fwu.uri();
                results.add(info);
            }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static List<ToolContent> toContent(List<Object> results) {
        List<ToolContent> content = new ArrayList<>();
        for (Object r : results) {
            if (r instanceof String s) {
                content.add(new ToolContent.TextItem(s));
            } else if (r instanceof Map m) {
                content.add(new ToolContent.TextItem(Json.encode(JsonValue.of(m))));
            } else if (r != null) {
                content.add(new ToolContent.TextItem(String.valueOf(r)));
            }
        }
        if (content.isEmpty()) content.add(new ToolContent.TextItem("OK"));
        return content;
    }

    @SuppressWarnings("unchecked")
    private static String joinResults(List<Object> results) {
        List<String> strings = new ArrayList<>();
        for (Object r : results) {
            if (r instanceof String s) strings.add(s);
            else if (r instanceof Map m) strings.add(Json.encode(JsonValue.of(m)));
            else if (r != null) strings.add(String.valueOf(r));
        }
        return strings.isEmpty() ? "OK" : String.join("\n", strings);
    }

    private static String extractText(JsonValue input) {
        if (!(input instanceof JsonValue.Obj obj)) return null;
        if (obj.values().get("text") instanceof JsonValue.Str s) return s.value();
        if (obj.values().get("message") instanceof JsonValue.Obj m) {
            if (m.values().get("text") instanceof JsonValue.Str s) return s.value();
        }
        return null;
    }

    private static String extractSkill(JsonValue input) {
        if (!(input instanceof JsonValue.Obj obj)) return null;
        if (obj.values().get("skill") instanceof JsonValue.Str s) return s.value();
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
