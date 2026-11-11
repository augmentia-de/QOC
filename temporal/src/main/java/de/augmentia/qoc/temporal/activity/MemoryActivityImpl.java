package de.augmentia.qoc.temporal.activity;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory memory store (per-process, not durable across restarts).
 * For durable memory, use QOC's session store or an external DB.
 */
public class MemoryActivityImpl implements MemoryActivity {

    private static final Map<String, java.util.List<String>> STORE = new ConcurrentHashMap<>();

    @Override
    public String remember(String scope, String content) {
        String s = scope == null || scope.isBlank() ? "default" : scope;
        STORE.computeIfAbsent(s, k -> new java.util.ArrayList<>()).add(content);
        return "remembered in scope '" + s + "'";
    }

    @Override
    public String recall(String scope) {
        String s = scope == null || scope.isBlank() ? "default" : scope;
        List<String> facts = STORE.get(s);
        if (facts == null || facts.isEmpty()) return "(no facts in scope '" + s + "')";
        return String.join("\n", facts);
    }
}
