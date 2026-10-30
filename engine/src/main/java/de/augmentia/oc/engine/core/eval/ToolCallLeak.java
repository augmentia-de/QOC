package de.augmentia.oc.engine.core.eval;

/**
 * Detects tool-call markup that some providers emit as plain assistant text
 * when structured tool calls are disabled. Such text is not a valid final
 * answer, even though the provider reports a normal finish.
 */
public final class ToolCallLeak {

    private static final String[] MARKERS = {
            "<|DSML|",
            "</DSML|",
            "<|tool",
            "[TOOL_CALLS]"
    };

    private ToolCallLeak() {}

    /**
     * Returns {@code true} when the text contains a known tool-call token
     * sequence. Full-width vertical bars and the underscore-like separator used
     * by some DeepSeek variants are normalized first.
     */
    public static boolean contains(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        String normalized = text.replace('\uFF5C', '|').replace('\u2581', '_');
        for (String marker : MARKERS) {
            if (normalized.contains(marker)) {
                return true;
            }
        }
        return false;
    }
}
