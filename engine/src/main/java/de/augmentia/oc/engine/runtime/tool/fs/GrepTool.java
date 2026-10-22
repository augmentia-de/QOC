package de.augmentia.oc.engine.runtime.tool.fs;

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
import java.util.List;
import java.util.Map;

/** {@code grep}: action and resource are the regex, not the search path (W-33). */
public final class GrepTool {

    public static final int MAX_HITS = 100;

    private GrepTool() {}

    public static ToolDefinition definition(FileSystem fileSystem, FileAccess access) {
        if (fileSystem == null || access == null) throw new IllegalArgumentException("dependencies must not be null");
        Schema input = Schema.obj(Map.of(
                "pattern", Schema.str(),
                "path", Schema.opt(Schema.str())));
        return new ToolDefinition(new ToolName("grep"), "Search file contents by regex",
                input, Option.none(),
                (value, context) -> execute(fileSystem, access, value), null);
    }

    static ToolOutcome execute(FileSystem fileSystem, FileAccess access, JsonValue value) {
        try {
            String regex = JsonArgs.requiredString(value, "pattern");
            String raw = JsonArgs.string(value, "path");
            Path root = raw == null
                    ? access.resolve(".", true).absolute()
                    : access.resolve(raw, true).absolute();

            List<FileSystem.SearchMatch> matches = fileSystem.search(root, regex);
            boolean truncated = matches.size() > MAX_HITS;
            List<FileSystem.SearchMatch> page = truncated ? matches.subList(0, MAX_HITS) : matches;

            StringBuilder text = new StringBuilder();
            for (FileSystem.SearchMatch match : page) {
                text.append(match.path()).append(':').append(match.line())
                        .append(": ").append(match.text()).append('\n');
            }
            Map<String, Object> metadata = truncated
                    ? Map.of("truncated", "yes", "total", matches.size())
                    : Map.of();
            return new ToolOutcome.Ok(new ToolResult(Option.none(),
                    List.of(new ToolContent.TextItem(text.toString())), metadata));
        } catch (IllegalArgumentException e) {
            return ReadTool.failure("grep", e.getMessage());
        } catch (UncheckedIOException e) {
            return ReadTool.failure("grep", "cannot search path");
        }
    }
}
