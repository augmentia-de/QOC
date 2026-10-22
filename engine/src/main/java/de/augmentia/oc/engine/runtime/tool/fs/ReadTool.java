package de.augmentia.oc.engine.runtime.tool.fs;

import de.augmentia.oc.engine.core.contract.tool.ToolContext;
import de.augmentia.oc.engine.core.contract.tool.ToolDefinition;
import de.augmentia.oc.engine.core.contract.tool.ToolOutcome;
import de.augmentia.oc.engine.core.contract.tool.ToolResult;
import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.json.Schema;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.SessionError;
import de.augmentia.oc.engine.core.model.ToolContent;
import de.augmentia.oc.engine.core.model.ToolName;
import de.augmentia.oc.engine.core.port.world.FileSystem;
import de.augmentia.oc.engine.runtime.tool.JsonArgs;

import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** {@code read}: text page, directory listing or a clear failure (W-35). */
public final class ReadTool {

    public static final int MAX_LINES = 2_000;
    public static final int MAX_BYTES = 50 * 1024;
    public static final int MAX_LINE_LENGTH = 2_000;

    private ReadTool() {}

    public static ToolDefinition definition(FileSystem fileSystem, FileAccess access) {
        if (fileSystem == null || access == null) throw new IllegalArgumentException("dependencies must not be null");
        Schema input = Schema.obj(Map.of(
                "path", Schema.str(),
                "offset", Schema.opt(Schema.intg()),
                "limit", Schema.opt(Schema.intg())));
        return new ToolDefinition(new ToolName("read"), "Read a file or directory",
                input, Option.none(),
                (value, context) -> execute(fileSystem, access, value), null);
    }

    static ToolOutcome execute(FileSystem fileSystem, FileAccess access, JsonValue value) {
        try {
            String raw = JsonArgs.requiredString(value, "path");
            int offset = Math.max(1, JsonArgs.intOr(value, "offset", 1));
            int limit = Math.min(MAX_LINES, Math.max(1, JsonArgs.intOr(value, "limit", MAX_LINES)));
            FileAccess.Resolved resolved = access.resolve(raw, true);

            FileSystem.FileStat stat = fileSystem.stat(resolved.absolute());
            if (stat.directory()) {
                return directory(fileSystem, resolved, offset, limit);
            }
            return text(fileSystem, resolved, offset, limit);
        } catch (IllegalArgumentException e) {
            return failure("read", e.getMessage());
        } catch (UncheckedIOException e) {
            return failure("read", "cannot read path");
        }
    }

    private static ToolOutcome directory(FileSystem fileSystem, FileAccess.Resolved resolved,
                                         int offset, int limit) {
        List<Path> entries = fileSystem.list(resolved.absolute());
        int from = Math.min(offset - 1, entries.size());
        int to = Math.min(from + limit, entries.size());
        List<Path> page = entries.subList(from, to);

        StringBuilder text = new StringBuilder("Read directory ")
                .append(resolved.absolute()).append(", entries ")
                .append(from + 1).append('-').append(to).append('\n');
        for (Path entry : page) {
            text.append(entry).append('\n');
        }
        boolean truncated = to < entries.size();
        Map<String, Object> metadata = truncated
                ? Map.of("truncated", "yes", "total", entries.size())
                : Map.of();
        return ok(text.toString(), metadata);
    }

    private static ToolOutcome text(FileSystem fileSystem, FileAccess.Resolved resolved,
                                    int offset, int limit) {
        byte[] bytes = fileSystem.read(resolved.absolute());
        if (isBinary(bytes)) {
            return failure("read", "binary file cannot be read");
        }
        String content = new String(bytes, StandardCharsets.UTF_8);
        String[] lines = content.split("\n", -1);
        int from = Math.min(offset - 1, lines.length);
        int to = Math.min(from + limit, lines.length);

        StringBuilder text = new StringBuilder("Read file ").append(resolved.absolute())
                .append(", lines ").append(from + 1).append('-').append(to).append('\n');
        int bytesUsed = 0;
        boolean truncated = to < lines.length;
        int shown = 0;
        for (int i = from; i < to; i++) {
            String line = lines[i];
            if (line.length() > MAX_LINE_LENGTH) {
                line = line.substring(0, MAX_LINE_LENGTH) + "…";
                truncated = true;
            }
            String rendered = (i + 1) + ": " + line + "\n";
            int renderedBytes = rendered.getBytes(StandardCharsets.UTF_8).length;
            if (bytesUsed + renderedBytes > MAX_BYTES) {
                truncated = true;
                break;
            }
            text.append(rendered);
            bytesUsed += renderedBytes;
            shown++;
        }
        Map<String, Object> metadata = truncated
                ? Map.of("truncated", "yes", "lines", shown, "total", lines.length)
                : Map.of();
        return ok(text.toString(), metadata);
    }

    private static boolean isBinary(byte[] bytes) {
        for (byte b : bytes) {
            if (b == 0) return true;
        }
        return false;
    }

    private static ToolOutcome ok(String text, Map<String, Object> metadata) {
        return new ToolOutcome.Ok(new ToolResult(Option.none(),
                List.of(new ToolContent.TextItem(text)), metadata));
    }

    public static ToolOutcome failure(String type, String message) {
        return new ToolOutcome.Failed(new SessionError.ToolExecutionError(type, message));
    }
}
