package de.augmentia.oc.engine.http;

import java.util.Map;

/** Prompt payload for the session endpoints. */
public record PromptRequest(String text, Map<String, Object> responseSchema) {
    public PromptRequest(String text) {
        this(text, null);
    }
}
