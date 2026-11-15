package de.augmentia.oc.engine.runtime.context;

import de.augmentia.oc.engine.core.model.ToolContent;

import java.util.List;

/**
 * Optional state-reduction for chat-agent tool results (Phase 6).
 *
 * <p>When enabled, large tool outputs are reduced to a compact delta before
 * they enter the model message history, mirroring the workflow runner's
 * {@code ContextAssembler} policy. The reduction is opt-in via the system
 * property {@code qoc.context.reduce-tool-results=true}; by default the
 * original behaviour (full output, wrapped in delimiters) is preserved so
 * existing conversations and tests are unaffected.</p>
 */
public final class ChatContextReducer {

    /** Default token budget above which a tool result is reduced. */
    public static final int DEFAULT_THRESHOLD_TOKENS = 4000;

    /** Maximum characters kept in the reduced summary. */
    public static final int SUMMARY_MAX_CHARS = 200;

    private ChatContextReducer() {}

    public static boolean enabled() {
        return Boolean.getBoolean("qoc.context.reduce-tool-results");
    }

    public static int threshold() {
        String v = System.getProperty("qoc.context.reduce-threshold-tokens");
        if (v == null || v.isBlank()) return DEFAULT_THRESHOLD_TOKENS;
        try { return Math.max(1, Integer.parseInt(v.trim())); }
        catch (NumberFormatException e) { return DEFAULT_THRESHOLD_TOKENS; }
    }

    /**
     * Reduces a tool result text to a compact delta when it exceeds the
     * threshold. Returns the original text unchanged when reduction is
     * disabled or the text is small enough.
     */
    public static String reduce(String toolName, String callId, String text) {
        if (text == null) return "";
        if (!enabled()) return text;
        int approxTokens = text.length() / 4;
        if (approxTokens <= threshold()) return text;
        String prefix = text.length() > SUMMARY_MAX_CHARS
                ? text.substring(0, SUMMARY_MAX_CHARS) + "…"
                : text;
        return "<tool-result name=\"" + (toolName == null ? "tool" : toolName)
                + "\" id=\"" + (callId == null ? "" : callId)
                + "\" reduced=\"true\">"
                + prefix
                + "\n[reduced: " + approxTokens + " tokens -> " + SUMMARY_MAX_CHARS + " chars]"
                + "</tool-result>";
    }

    /**
     * Convenience: reduce the text content of a list of ToolContent items.
     */
    public static String reduceContent(String toolName, String callId, List<ToolContent> content) {
        if (content == null || content.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (ToolContent c : content) {
            if (c instanceof ToolContent.TextItem t) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(t.text());
            }
        }
        return reduce(toolName, callId, sb.toString());
    }
}