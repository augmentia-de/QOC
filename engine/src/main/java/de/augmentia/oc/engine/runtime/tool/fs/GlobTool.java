package de.augmentia.oc.engine.runtime.tool.fs;

import de.augmentia.oc.engine.core.contract.permission.PermissionGate;
import de.augmentia.oc.engine.core.contract.permission.PermissionRequest;
import de.augmentia.oc.engine.core.contract.tool.ToolDefinition;
import de.augmentia.oc.engine.core.contract.tool.ToolOutcome;
import de.augmentia.oc.engine.core.contract.tool.ToolResult;
import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.json.Schema;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.ToolContent;
import de.augmentia.oc.engine.core.model.ToolName;
import de.augmentia.oc.engine.core.port.world.FileSystem;
import de.augmentia.oc.engine.runtime.tool.JsonArgs;

import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** {@code glob}: action and resource are the pattern (W-34). */
public final class GlobTool {

    public static final int MAX_HITS = 100;
    public static final int MAX_PAGE = 1_000;
    public static final long DEFAULT_TIMEOUT_MILLIS = 10_000;
    public static final long MAX_TIMEOUT_MILLIS = 60_000;

    private GlobTool() {}

    public static ToolDefinition definition(FileSystem fileSystem, FileAccess access) {
        return definition(fileSystem, access, null);
    }

    public static ToolDefinition definition(FileSystem fileSystem, FileAccess access, PermissionGate permissionGate) {
        if (fileSystem == null || access == null) throw new IllegalArgumentException("dependencies must not be null");
        Schema input = Schema.obj(Map.of(
                "pattern", Schema.str(),
                "path", Schema.opt(Schema.str()),
                "maxResults", Schema.opt(Schema.intg()),
                "cursor", Schema.opt(Schema.str()),
                "timeout", Schema.opt(Schema.lng()),
                "hidden", Schema.opt(Schema.bool()),
                "ignore", Schema.opt(Schema.bool()),
                "depth", Schema.opt(Schema.intg())));
        return new ToolDefinition(new ToolName("glob"), "Find files by pattern",
                input, Option.none(),
                (value, context) -> {
                    if (permissionGate != null) {
                        try {
                            String raw = JsonArgs.string(value, "path");
                            if (raw != null && !raw.isBlank()) {
                                FileAccess.Resolved resolved = access.resolve(raw, true);
                                if (resolved.external()) {
                                    PermissionRequest req = new PermissionRequest(
                                            de.augmentia.oc.engine.core.model.PermissionRequestId.create(),
                                            context.sessionId(),
                                            "external_directory",
                                            List.of(resolved.boundary().toString()),
                                            List.of(resolved.boundary().toString() + "/*"),
                                            Map.of("tool", "glob", "path", raw),
                                            null, null);
                                    permissionGate.assertAllowed(req);
                                }
                            }
                        } catch (IllegalArgumentException ignored) {
                            // leave validation to execute()
                        }
                    }
                    return execute(fileSystem, access, value);
                }, null);
    }

    static ToolOutcome execute(FileSystem fileSystem, FileAccess access, JsonValue value) {
        try {
            String pattern = JsonArgs.requiredString(value, "pattern");
            String raw = JsonArgs.string(value, "path");
            Path root = raw == null
                    ? access.resolve(".", true).absolute()
                    : access.resolve(raw, true).absolute();

            int offset = cursor(value);
            int maxResults = clamp(JsonArgs.intOr(value, "maxResults", MAX_HITS), 1, MAX_PAGE);
            int depth = clamp(JsonArgs.intOr(value, "depth", 64), 1, 256);
            long timeout = clamp(JsonArgs.longOr(value, "timeout", DEFAULT_TIMEOUT_MILLIS),
                    0, MAX_TIMEOUT_MILLIS);
            FileSystem.GlobOptions options = new FileSystem.GlobOptions(
                    offset, maxResults, depth,
                    JsonArgs.boolOr(value, "hidden", false),
                    JsonArgs.boolOr(value, "ignore", true),
                    Duration.ofMillis(timeout));
            FileSystem.GlobOutcome outcome = fileSystem.glob(root, pattern, options);

            StringBuilder text = new StringBuilder();
            for (Path match : outcome.matches()) {
                text.append(match).append('\n');
            }
            Map<String, Object> metadata = new LinkedHashMap<>();
            if (outcome.truncated()) {
                metadata.put("truncated", "yes");
                metadata.put("nextCursor", String.valueOf(offset + outcome.matches().size()));
            }
            if (outcome.total() > 0) metadata.put("total", outcome.total());
            if (outcome.timedOut()) metadata.put("timedOut", "yes");
            if (outcome.skipped() > 0) metadata.put("skipped", outcome.skipped());
            return new ToolOutcome.Ok(new ToolResult(Option.none(),
                    List.of(new ToolContent.TextItem(text.toString())), metadata));
        } catch (IllegalArgumentException e) {
            return ReadTool.failure("glob", e.getMessage());
        } catch (UncheckedIOException e) {
            return ReadTool.failure("glob", "cannot search path");
        }
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
}
