package de.augmentia.oc.engine.http;

import de.augmentia.oc.engine.runtime.callback.EventPublisher;
import de.augmentia.oc.engine.core.json.Json;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Quarkus-specific EventPublisher that wraps EventBus.
 */
public class QuarkusEventPublisher implements EventPublisher {
    private final EventBus eventBus;

    public QuarkusEventPublisher(EventBus eventBus) {
        this.eventBus = eventBus;
    }

    @Override
    public void publishLive(String sessionId, String json) {
        if (eventBus != null) {
            eventBus.publishLive(sessionId, json);
        }
    }

    @Override
    public void publishToolResult(String sessionId, String toolCallId, String resultText) {
        if (eventBus == null) return;
        try {
            // Same shape as a ModelEvent.ToolResultProvided so the UI renders
            // tool calls and their results through one code path.
            Map<String, Object> outcome = new LinkedHashMap<>();
            outcome.put("_type", "Text");
            outcome.put("text", resultText == null ? "" : resultText);
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("_type", "ToolResultProvided");
            map.put("callId", toolCallId);
            map.put("outcome", outcome);
            map.put("providerExecuted", false);
            String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(map);
            eventBus.publishLive(sessionId, json);
        } catch (Exception e) {
            // Ignore
        }
    }
}