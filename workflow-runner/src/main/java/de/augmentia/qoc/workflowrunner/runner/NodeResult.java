package de.augmentia.qoc.workflowrunner.runner;

import java.util.Map;

/**
 * Result of a single workflow node execution.
 */
public record NodeResult(String text, Map<String, Object> structured) {
    public String text() { return text == null ? "" : text; }
    public Map<String, Object> structured() { return structured; }
    public Object get(String key) { return structured != null ? structured.get(key) : null; }
}