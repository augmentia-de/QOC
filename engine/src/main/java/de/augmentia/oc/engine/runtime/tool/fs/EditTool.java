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
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** {@code edit}: exact-anchor replacement; multiple matches fail. */
public final class EditTool {

    private EditTool() {}

    public static ToolDefinition definition(FileSystem fileSystem, FileAccess access) {
        if (fileSystem == null || access == null) throw new IllegalArgumentException("dependencies must not be null");
        Schema input = Schema.obj(Map.of("path", Schema.str(), "old", Schema.str(), "new", Schema.str()));
        return new ToolDefinition(new ToolName("edit"), "Replace an exact text block",
                input, Option.none(),
                (value, context) -> execute(fileSystem, access, value), null);
    }

    static ToolOutcome execute(FileSystem fileSystem, FileAccess access, JsonValue value) {
        try {
            String raw = JsonArgs.requiredString(value, "path");
            String oldText = JsonArgs.requiredString(value, "old");
            String newText = JsonArgs.string(value, "new");
            if (newText == null) newText = "";
            FileAccess.Resolved resolved = access.resolve(raw, false);

            String content = fileSystem.readText(resolved.absolute());
            int first = content.indexOf(oldText);
            if (first < 0) {
                return ReadTool.failure("edit", "old text not found");
            }
            if (content.indexOf(oldText, first + oldText.length()) >= 0) {
                return ReadTool.failure("edit", "old text matches more than once");
            }
            String updated = content.substring(0, first) + newText + content.substring(first + oldText.length());
            fileSystem.write(resolved.absolute(), updated.getBytes(StandardCharsets.UTF_8));

            JsonValue output = JsonValue.of(Map.of(
                    "operation", JsonValue.of("edit"),
                    "target", JsonValue.of(resolved.absolute().toString()),
                    "resource", JsonValue.of(resolved.resource())));
            Map<String, Object> metadata = Map.of("files", List.of(Map.of(
                    "path", resolved.absolute().toString(), "kind", "modified")));
            return new ToolOutcome.Ok(new ToolResult(Option.some(output),
                    List.of(new ToolContent.TextItem("Edited " + resolved.absolute())), metadata));
        } catch (IllegalArgumentException e) {
            return ReadTool.failure("edit", e.getMessage());
        } catch (UncheckedIOException e) {
            return ReadTool.failure("edit", "cannot edit path");
        }
    }
}
