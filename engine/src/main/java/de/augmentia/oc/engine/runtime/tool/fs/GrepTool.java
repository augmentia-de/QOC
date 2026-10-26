package de.augmentia.oc.engine.runtime.tool.fs;

import de.augmentia.oc.engine.core.contract.permission.PermissionGate;
import de.augmentia.oc.engine.core.contract.permission.PermissionRequest;
import de.augmentia.oc.engine.core.contract.tool.ToolDefinition;
import de.augmentia.oc.engine.core.contract.tool.ToolOutcome;
import de.augmentia.oc.engine.core.contract.tool.ToolResult;
import de.augmentia.oc.engine.core.error.StoreFailure;
import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.json.Schema;
import de.augmentia.oc.engine.core.model.ArtifactRef;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.SessionId;
import de.augmentia.oc.engine.core.model.ToolContent;
import de.augmentia.oc.engine.core.model.ToolName;
import de.augmentia.oc.engine.core.port.store.ToolOutputStore;
import de.augmentia.oc.engine.core.port.world.FileSystem;
import de.augmentia.oc.engine.runtime.tool.JsonArgs;

import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** {@code grep}: action and resource are the regex, not the search path (W-33). */
public final class GrepTool {

    public static final int MAX_HITS = 100;
    public static final int MAX_PAGE = 1_000;
    public static final int MAX_CONTEXT = 10;
    public static final long DEFAULT_TIMEOUT_MILLIS = 10_000;
    public static final long MAX_TIMEOUT_MILLIS = 60_000;

    private GrepTool() {}

    public static ToolDefinition definition(FileSystem fileSystem, FileAccess access) {
        return definition(fileSystem, access, null, null);
    }

    public static ToolDefinition definition(FileSystem fileSystem, FileAccess access, PermissionGate permissionGate) {
        return definition(fileSystem, access, permissionGate, null);
    }

    public static ToolDefinition definition(FileSystem fileSystem, FileAccess access,
                                            PermissionGate permissionGate, ToolOutputStore artifacts) {
        if (fileSystem == null || access == null) throw new IllegalArgumentException("dependencies must not be null");
        Schema input = Schema.obj(Map.ofEntries(
                Map.entry("pattern", Schema.str()),
                Map.entry("path", Schema.opt(Schema.str())),
                Map.entry("caseSensitive", Schema.opt(Schema.bool())),
                Map.entry("context", Schema.opt(Schema.intg())),
                Map.entry("maxResults", Schema.opt(Schema.intg())),
                Map.entry("maxPerFile", Schema.opt(Schema.intg())),
                Map.entry("cursor", Schema.opt(Schema.str())),
                Map.entry("timeout", Schema.opt(Schema.lng())),
                Map.entry("hidden", Schema.opt(Schema.bool())),
                Map.entry("ignore", Schema.opt(Schema.bool())),
                Map.entry("depth", Schema.opt(Schema.intg()))));
        return new ToolDefinition(new ToolName("grep"), "Search file contents by regex",
                input, Option.none(),
                (value, context) -> {
                    if (permissionGate != null) {
                        try {
                            String raw = JsonArgs.string(value, "path");
                            if (raw != null && !raw.isBlank() && !raw.startsWith(ArtifactRef.SCHEME)) {
                                FileAccess.Resolved resolved = access.resolve(raw, true);
                                if (resolved.external()) {
                                    PermissionRequest req = new PermissionRequest(
                                            de.augmentia.oc.engine.core.model.PermissionRequestId.create(),
                                            context.sessionId(),
                                            "external_directory",
                                            List.of(resolved.boundary().toString()),
                                            List.of(resolved.boundary().toString() + "/*"),
                                            Map.of("tool", "grep", "path", raw),
                                            null, null);
                                    permissionGate.assertAllowed(req);
                                }
                            }
                        } catch (IllegalArgumentException ignored) {
                            // leave validation to execute()
                        }
                    }
                    return execute(fileSystem, access, artifacts, value, context.sessionId());
                }, null);
    }

    static ToolOutcome execute(FileSystem fileSystem, FileAccess access, JsonValue value) {
        return execute(fileSystem, access, null, value, null);
    }

    static ToolOutcome execute(FileSystem fileSystem, FileAccess access, ToolOutputStore artifacts,
                               JsonValue value, SessionId session) {
        try {
            String regex = JsonArgs.requiredString(value, "pattern");
            String raw = JsonArgs.string(value, "path");
            int offset = cursor(value);
            int maxResults = clamp(JsonArgs.intOr(value, "maxResults", MAX_HITS), 1, MAX_PAGE);
            int maxPerFile = clamp(JsonArgs.intOr(value, "maxPerFile", 20), 1, MAX_PAGE);
            int context = clamp(JsonArgs.intOr(value, "context", 0), 0, MAX_CONTEXT);
            int depth = clamp(JsonArgs.intOr(value, "depth", 64), 1, 256);
            long timeout = clamp(JsonArgs.longOr(value, "timeout", DEFAULT_TIMEOUT_MILLIS),
                    0, MAX_TIMEOUT_MILLIS);
            boolean caseSensitive = JsonArgs.boolOr(value, "caseSensitive", true);

            if (raw != null && raw.startsWith(ArtifactRef.SCHEME)) {
                return artifact(artifacts, raw, session, regex, caseSensitive, offset,
                        maxResults, maxPerFile, context, timeout);
            }

            Path root = raw == null
                    ? access.resolve(".", true).absolute()
                    : access.resolve(raw, true).absolute();
            FileSystem.SearchOptions options = new FileSystem.SearchOptions(
                    caseSensitive, offset, maxResults, context, depth,
                    JsonArgs.boolOr(value, "hidden", false),
                    JsonArgs.boolOr(value, "ignore", true), 8L * 1024L * 1024L,
                    maxPerFile, Duration.ofMillis(timeout));
            FileSystem.SearchOutcome outcome = fileSystem.search(root, regex, options);
            List<LineMatch> matches = new ArrayList<>();
            for (FileSystem.SearchMatch match : outcome.matches()) {
                matches.add(new LineMatch(match.path().toString(), match.line(), match.text(),
                        match.before(), match.after()));
            }
            return render(matches, outcome.total(), outcome.truncated(),
                    outcome.timedOut(), outcome.skipped(), offset);
        } catch (IllegalArgumentException e) {
            return ReadTool.failure("grep", e.getMessage());
        } catch (StoreFailure e) {
            return ReadTool.failure("grep", e.getMessage());
        } catch (UncheckedIOException e) {
            return ReadTool.failure("grep", "cannot search path");
        }
    }

    private static ToolOutcome artifact(ToolOutputStore artifacts, String raw, SessionId session,
                                        String regex, boolean caseSensitive, int offset,
                                        int maxResults, int maxPerFile, int context, long timeout) {
        if (artifacts == null) return ReadTool.failure("grep", "artifact store is not configured");
        if (session == null) return ReadTool.failure("grep", "artifact requires an active session");
        ArtifactRef.Parsed parsed = ArtifactRef.parse(raw);
        if (!parsed.session().equals(session)) {
            return ReadTool.failure("grep", "artifact belongs to another session");
        }
        if (artifacts.find(session, parsed.id()).isEmpty()) {
            return ReadTool.failure("grep", "unknown artifact: " + raw);
        }
        byte[] bytes = artifacts.load(session, parsed.id());
        if (binary(bytes)) return ReadTool.failure("grep", "binary artifact cannot be searched");

        Pattern pattern;
        try {
            int flags = caseSensitive ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
            pattern = Pattern.compile(regex, flags);
        } catch (PatternSyntaxException e) {
            return ReadTool.failure("grep", "invalid regex: " + regex);
        }
        String[] lines = new String(bytes, StandardCharsets.UTF_8).split("\n", -1);
        List<String> allLines = Arrays.asList(lines);
        List<LineMatch> matches = new ArrayList<>();
        long deadline = deadline(timeout);
        int total = 0;
        int perFile = 0;
        boolean truncated = false;
        boolean timedOut = false;
        for (int i = 0; i < lines.length; i++) {
            if ((i & 1023) == 0 && expired(deadline)) {
                timedOut = true;
                break;
            }
            if (!pattern.matcher(lines[i]).find()) continue;
            perFile++;
            if (perFile > maxPerFile) {
                truncated = true;
                break;
            }
            total++;
            if (total <= offset) continue;
            if (matches.size() >= maxResults) {
                truncated = true;
                continue;
            }
            int beforeFrom = Math.max(0, i - context);
            int afterTo = Math.min(lines.length, i + context + 1);
            matches.add(new LineMatch(raw, i + 1, lines[i],
                    allLines.subList(beforeFrom, i), allLines.subList(i + 1, afterTo)));
        }
        truncated = truncated || total > offset + matches.size();
        return render(matches, total, truncated, timedOut, 0, offset);
    }

    private static ToolOutcome render(List<LineMatch> matches, int total, boolean truncated,
                                      boolean timedOut, int skipped, int offset) {
        StringBuilder text = new StringBuilder();
        for (LineMatch match : matches) {
            appendContext(text, match, true);
            text.append(match.source()).append(':').append(match.line())
                    .append(": ").append(match.text()).append('\n');
            appendContext(text, match, false);
        }
        Map<String, Object> metadata = metadata(total, truncated, timedOut, skipped, offset,
                matches.size());
        return new ToolOutcome.Ok(new ToolResult(Option.none(),
                List.of(new ToolContent.TextItem(text.toString())), metadata));
    }

    private static void appendContext(StringBuilder text, LineMatch match, boolean before) {
        List<String> lines = before ? match.before() : match.after();
        for (int i = 0; i < lines.size(); i++) {
            int line = before ? match.line() - lines.size() + i : match.line() + i + 1;
            text.append(match.source()).append(before ? '-' : '+').append(line)
                    .append(before ? "- " : "+ ").append(lines.get(i)).append('\n');
        }
    }

    private static Map<String, Object> metadata(int total, boolean truncated, boolean timedOut,
                                                int skipped, int offset, int returned) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (truncated) {
            metadata.put("truncated", "yes");
            metadata.put("nextCursor", String.valueOf(offset + returned));
        }
        if (total > 0) metadata.put("total", total);
        if (timedOut) metadata.put("timedOut", "yes");
        if (skipped > 0) metadata.put("skipped", skipped);
        return metadata;
    }

    private static int cursor(JsonValue value) {
        String cursor = JsonArgs.string(value, "cursor");
        if (cursor == null || cursor.isBlank()) return 0;
        try {
            return Math.max(0, Integer.parseInt(cursor));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid cursor: " + cursor);
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }

    private static long deadline(long timeoutMillis) {
        return timeoutMillis <= 0 ? 0L : System.nanoTime() + Duration.ofMillis(timeoutMillis).toNanos();
    }

    private static boolean expired(long deadline) {
        return deadline != 0L && System.nanoTime() > deadline;
    }

    private static boolean binary(byte[] bytes) {
        int limit = Math.min(bytes.length, 8 * 1024);
        for (int i = 0; i < limit; i++) {
            if (bytes[i] == 0) return true;
        }
        return false;
    }

    private record LineMatch(String source, int line, String text,
                             List<String> before, List<String> after) {
        private LineMatch {
            before = before == null ? List.of() : List.copyOf(before);
            after = after == null ? List.of() : List.copyOf(after);
        }
    }
}
