package de.augmentia.oc.engine.core.eval;

import de.augmentia.oc.engine.core.json.Json;
import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.json.Result;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Recovers tool calls from provider-native markup that leaked into the text
 * channel.
 *
 * <p>The parser understands the DeepSeek DSML shape observed in production:</p>
 * <pre>
 * &lt;|DSML| calls&gt;
 * &lt;|DSML| invoke name="shell"&gt;
 * &lt;|DSML| parameter name="command" string="true"&gt;ls&lt;/|DSML| parameter&gt;
 * &lt;/|DSML| invoke&gt;
 * &lt;/|DSML| calls&gt;
 * </pre>
 *
 * <p>Full-width bars and repeated bars are tolerated. The parser is deliberately
 * narrow: it never invents calls, and the caller must still validate names
 * against the prepared tool snapshot and run the normal permission gate.</p>
 */
public final class ToolCallLeakParser {

    /** Hard bound for recovered calls; the model should never emit more. */
    public static final int MAX_CALLS = 8;

    /** Hard bound for one parameter value to avoid unbounded memory use. */
    public static final int MAX_PARAMETER_LENGTH = 100_000;

    private static final Pattern INVOKE = Pattern.compile(
            "<[^>]*?DSML[^>]*?invoke\\s+name\\s*=\\s*\"([^\"]+)\"[^>]*>",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern INVOKE_CLOSE = Pattern.compile(
            "</[^>]*?DSML[^>]*?invoke[^>]*>",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern PARAMETER = Pattern.compile(
            "<[^>]*?DSML[^>]*?parameter\\s+name\\s*=\\s*\"([^\"]+)\"([^>]*)>(.*?)"
                    + "</[^>]*?DSML[^>]*?parameter[^>]*>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private ToolCallLeakParser() {}

    /**
     * Extracts every well-formed {@code invoke} block from {@code text}.
     * Malformed blocks are skipped; the returned list is immutable.
     */
    public static List<SalvagedCall> parse(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<SalvagedCall> calls = new ArrayList<>();
        Matcher invoke = INVOKE.matcher(text);
        while (invoke.find() && calls.size() < MAX_CALLS) {
            String name = unescape(invoke.group(1)).trim();
            if (name.isEmpty()) {
                continue;
            }
            int bodyStart = invoke.end();
            Matcher close = INVOKE_CLOSE.matcher(text);
            close.region(bodyStart, text.length());
            if (!close.find()) {
                // A truncated block is not safe to salvage; caller falls back
                // to the typed retry path.
                continue;
            }
            int bodyEnd = close.start();
            String body = text.substring(bodyStart, bodyEnd);
            Map<String, JsonValue> input = parameters(body);
            calls.add(new SalvagedCall(name, JsonValue.of(input)));
        }
        return List.copyOf(calls);
    }

    private static Map<String, JsonValue> parameters(String body) {
        Map<String, JsonValue> input = new LinkedHashMap<>();
        Matcher parameter = PARAMETER.matcher(body);
        while (parameter.find()) {
            String name = unescape(parameter.group(1)).trim();
            if (name.isEmpty()) {
                continue;
            }
            String attributes = parameter.group(2) == null ? "" : parameter.group(2);
            String raw = parameter.group(3) == null ? "" : parameter.group(3);
            if (raw.length() > MAX_PARAMETER_LENGTH) {
                raw = raw.substring(0, MAX_PARAMETER_LENGTH);
            }
            boolean string = attributes.contains("string=\"true\"")
                    || attributes.contains("string='true'");
            input.put(name, string ? JsonValue.of(unescape(raw)) : coerce(raw));
        }
        return Map.copyOf(input);
    }

    private static JsonValue coerce(String raw) {
        String trimmed = raw == null ? "" : raw.trim();
        if (trimmed.isEmpty()) {
            return JsonValue.of("");
        }
        Result<JsonValue> parsed = Json.parse(trimmed);
        if (parsed instanceof Result.Ok<JsonValue> ok) {
            return ok.valueOrNull();
        }
        return JsonValue.of(unescape(trimmed));
    }

    private static String unescape(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return value
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&");
    }

    /** One recovered tool call. */
    public record SalvagedCall(String name, JsonValue input) {
        public SalvagedCall {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("tool name must not be blank");
            }
            if (input == null) {
                throw new IllegalArgumentException("input must not be null");
            }
        }
    }
}
