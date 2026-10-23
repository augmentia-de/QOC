package de.augmentia.oc.engine.core.eval;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads prompt templates from {@code /prompts/<id>.txt} on the classpath.
 * Prompts are data, not string literals (PR-1): a missing prompt is a defect.
 */
public final class PromptLoader {

    private static final String BASE = "/prompts/";
    private static final Map<String, String> CACHE = new ConcurrentHashMap<>();

    private PromptLoader() {}

    /** Loads and caches the template with the given id. */
    public static String get(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("prompt id must not be blank");
        }
        return CACHE.computeIfAbsent(id, PromptLoader::load);
    }

    /** Replaces {@code {key}} placeholders with the supplied values. */
    public static String render(String id, Map<String, String> variables) {
        String text = get(id);
        if (variables == null || variables.isEmpty()) return text;
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            String value = entry.getValue() == null ? "" : entry.getValue();
            text = text.replace("{" + entry.getKey() + "}", value);
        }
        return text;
    }

    private static String load(String id) {
        String path = BASE + id + ".txt";
        try (InputStream input = PromptLoader.class.getResourceAsStream(path)) {
            if (input == null) {
                throw new IllegalArgumentException("missing prompt: " + id);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalArgumentException("cannot load prompt: " + id, e);
        }
    }
}
