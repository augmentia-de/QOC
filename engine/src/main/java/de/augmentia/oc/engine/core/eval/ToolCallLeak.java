package de.augmentia.oc.engine.core.eval;

import java.util.Locale;

/**
 * Detects tool-call markup that some providers emit as plain assistant text.
 *
 * <p>Providers occasionally stream their native tool-call encoding (DeepSeek
 * DSML, generic {@code <|tool...|>} or {@code [TOOL_CALLS]} markers) into the
 * normal text channel instead of producing structured tool calls. Such text is
 * not a valid final answer.</p>
 */
public final class ToolCallLeak {

    private ToolCallLeak() {}

    /**
     * Returns {@code true} when the text contains a known tool-call token
     * sequence. Full-width vertical bars, box-drawing bars and the underscore-
     * like separator used by some DeepSeek variants are normalized first; runs
     * of consecutive bars collapse to a single bar and the comparison is
     * case-insensitive.
     *
     * <p>A bare marker is not enough: the surrounding text must also contain an
     * {@code invoke}, {@code parameter}, {@code calls}, {@code call} or
     * {@code [tool_calls]} signature, so that prose that merely quotes the
     * marker is not flagged.</p>
     */
    public static boolean contains(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        String normalized = normalize(text).toLowerCase(Locale.ROOT);
        boolean hasMarker = normalized.contains("<|dsml|")
                || normalized.contains("</|dsml|")
                || normalized.contains("<|tool")
                || normalized.contains("[tool_calls]");
        if (!hasMarker) {
            return false;
        }
        return normalized.contains("invoke")
                || normalized.contains("parameter")
                || normalized.contains("calls")
                || normalized.contains("call")
                || normalized.contains("[tool_calls]");
    }

    private static String normalize(String text) {
        StringBuilder first = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '\uFF5C', '\u2502', '\u01C0' -> first.append('|');
                case '\u2581' -> first.append('_');
                default -> first.append(c);
            }
        }
        StringBuilder out = new StringBuilder(first.length());
        boolean prevBar = false;
        for (int i = 0; i < first.length(); i++) {
            char c = first.charAt(i);
            if (c == '|') {
                if (prevBar) continue;
                prevBar = true;
            } else {
                prevBar = false;
            }
            out.append(c);
        }
        return out.toString();
    }
}