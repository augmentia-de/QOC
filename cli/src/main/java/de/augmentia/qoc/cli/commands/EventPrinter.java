package de.augmentia.qoc.cli.commands;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Prints live model events to the terminal. Text deltas go to stdout, tool and
 * subagent events to stderr so piped stdout stays clean.
 */
final class EventPrinter implements Consumer<JsonNode> {
    private final AtomicBoolean textSeen;

    EventPrinter(AtomicBoolean textSeen) {
        this.textSeen = textSeen;
    }

    @Override
    public void accept(JsonNode ev) {
        String type = ev.path("_type").asText("");
        switch (type) {
            case "TextDelta" -> {
                String text = ev.path("text").asText("");
                if (!text.isEmpty()) {
                    System.out.print(text);
                    System.out.flush();
                    textSeen.set(true);
                }
            }
            case "TextEnded" -> System.out.println();
            case "ToolCallRequested" -> System.err.println("[tool] " + ev.path("name").asText(""));
            case "ToolResultProvided" -> {
                JsonNode outcome = ev.path("outcome");
                String text = outcome.path("text").asText("");
                if (!text.isBlank()) {
                    String trimmed = text.length() > 300 ? text.substring(0, 300) + "…" : text;
                    System.err.println("[tool result] " + trimmed.replace('\n', ' '));
                }
            }
            case "ToolFailed", "ProviderFailed" ->
                    System.err.println("[error] " + ev.path("message").asText(""));
            case "RetryScheduled" ->
                    System.err.println("[retry] attempt " + ev.path("attempt").asInt() + " in " + ev.path("delay").asText(""));
            case "SubagentStarted" ->
                    System.err.println("[subagent] started " + ev.path("agent").asText(""));
            case "SubagentFinished" ->
                    System.err.println("[subagent] " + ev.path("state").asText("finished"));
            case "SubagentCancelled" ->
                    System.err.println("[subagent] cancelled");
            default -> {
                // LlmRequest/LlmResponse and other types intentionally ignored.
            }
        }
    }
}
