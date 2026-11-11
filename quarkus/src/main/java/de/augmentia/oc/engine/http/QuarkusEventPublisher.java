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
        if (eventBus != null) {
            try {
                java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
                map.put("type", "tool_result");
                map.put("toolCallId", toolCallId);
                map.put("result", resultText);
                String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(map);
                eventBus.publishLive(sessionId, json);
            } catch (Exception e) {
                // Ignore
            }
        }
    }
}