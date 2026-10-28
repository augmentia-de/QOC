package de.augmentia.oc.engine.runtime.tool.a2a;

import de.augmentia.oc.engine.core.contract.tool.ToolDefinition;
import de.augmentia.oc.engine.core.contract.tool.ToolOutcome;
import de.augmentia.oc.engine.core.contract.tool.ToolResult;
import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.json.Schema;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.SessionError;
import de.augmentia.oc.engine.core.model.ToolContent;
import de.augmentia.oc.engine.core.model.ToolName;
import io.a2a.A2A;
import io.a2a.client.Client;
import io.a2a.client.ClientEvent;
import io.a2a.client.MessageEvent;
import io.a2a.client.TaskEvent;
import io.a2a.client.TaskUpdateEvent;
import io.a2a.spec.AgentCard;
import io.a2a.spec.Message;
import io.a2a.spec.Part;
import io.a2a.spec.TextPart;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

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
                "message", Schema.object(Map.of("type", Schema.string(), "text", Schema.string()), false),
                "text", Schema.string()
        ), false);
        return new ToolDefinition(new ToolName(name), description, schema, Option.none(),
                (input, context) -> execute(card, client, input), de.augmentia.oc.engine.core.contract.tool.ToolOptions.defaults());
    }

    private static ToolOutcome execute(AgentCard card, Client client, JsonValue input) {
        try {
            String text = extractText(input);
            if (text == null || text.isBlank()) {
                text = de.augmentia.oc.engine.core.json.Json.encode(input);
            }
            Message message = A2A.toUserMessage(text);
            CompletableFuture<String> future = new CompletableFuture<>();
            java.util.List<java.util.function.BiConsumer<io.a2a.client.ClientEvent, io.a2a.spec.AgentCard>> consumers =
                    new ArrayList<>();
            consumers.add((event, agentCard) -> {
                String extracted = extractEventText(event);
                if (extracted != null && !future.isDone()) {
                    future.complete(extracted);
                }
            });
            client.sendMessage(message, consumers, (Throwable t) -> {
                if (t != null && !future.isDone()) {
                    future.completeExceptionally(t);
                }
            }, new io.a2a.client.transport.spi.interceptors.ClientCallContext(java.util.Map.of(), java.util.Map.of()));
            // Wait for any event
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(300);
            while (System.nanoTime() < deadline && !future.isDone()) {
                Thread.sleep(50);
            }
            if (!future.isDone()) {
                return failure("timeout", "A2A call timed out for " + card.name());
            }
            String result;
            try {
                result = future.getNow(null);
            } catch (Exception e) {
                result = future.join();
            }
            if (result == null || result.isBlank()) {
                result = "OK";
            }
            return new ToolOutcome.Ok(new ToolResult(Option.none(),
                    List.of(new ToolContent.TextItem(result)), Map.of("agent", card.name())));
        } catch (Exception e) {
            LOG.warning("A2A tool failed: " + e.getMessage());
            return failure("a2a_error", e.getMessage() == null ? "A2A call failed" : e.getMessage());
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

    private static String extractEventText(ClientEvent event) {
        if (event instanceof MessageEvent me) {
            Message msg = me.getMessage();
            if (msg == null) return null;
            StringBuilder sb = new StringBuilder();
            if (msg.getParts() != null) {
                for (Part<?> part : msg.getParts()) {
                    if (part instanceof TextPart tp && tp.getText() != null) {
                        sb.append(tp.getText());
                    }
                }
            }
            return sb.length() == 0 ? null : sb.toString();
        }
        if (event instanceof TaskEvent te) {
            if (te.getTask() != null && te.getTask().getStatus() != null
                    && te.getTask().getStatus().message() != null) {
                return extractText(te.getTask().getStatus().message());
            }
            return null;
        }
        if (event instanceof TaskUpdateEvent tue) {
            if (tue.getTask() != null && tue.getTask().getStatus() != null && tue.getTask().getStatus().message() != null) {
                return extractText(tue.getTask().getStatus().message());
            }
            return null;
        }
        return null;
    }

    private static String extractText(Message msg) {
        if (msg == null || msg.getParts() == null) return null;
        StringBuilder sb = new StringBuilder();
        for (Part<?> part : msg.getParts()) {
            if (part instanceof TextPart tp && tp.getText() != null) {
                sb.append(tp.getText());
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private static String normalize(String value) {
        if (value == null) return "agent";
        return value.replaceAll("[^A-Za-z0-9_-]", "_").toLowerCase();
    }

    private static ToolOutcome failure(String type, String message) {
        return new ToolOutcome.Failed(new SessionError.ToolExecutionError(type, message));
    }
}
