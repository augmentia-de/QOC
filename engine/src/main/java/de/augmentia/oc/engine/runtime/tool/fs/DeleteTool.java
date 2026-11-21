package de.augmentia.oc.engine.runtime.tool.fs;

import de.augmentia.oc.engine.core.contract.permission.PermissionGate;
import de.augmentia.oc.engine.core.contract.permission.PermissionRequest;
import de.augmentia.oc.engine.core.contract.tool.ToolDefinition;
import de.augmentia.oc.engine.core.contract.tool.ToolOptions;
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
import java.util.List;
import java.util.Map;

/** {@code delete}: action {@code edit}, removes a file below the workspace. */
public final class DeleteTool {

    private DeleteTool() {}

    public static ToolDefinition definition(FileSystem fileSystem, FileAccess access) {
        return definition(fileSystem, access, null);
    }

    public static ToolDefinition definition(FileSystem fileSystem, FileAccess access,
                                            PermissionGate permissionGate) {
        if (fileSystem == null || access == null) {
            throw new IllegalArgumentException("dependencies must not be null");
        }
        Schema input = Schema.obj(Map.of("path", Schema.str()));
        return new ToolDefinition(new ToolName("delete"), "Delete a file",
                input, Option.none(),
                (value, context) -> {
                    if (permissionGate != null) {
                        try {
                            String raw = JsonArgs.requiredString(value, "path");
                            FileAccess.Resolved resolved = access.resolve(raw, false);
                            if (resolved.external()) {
                                PermissionRequest req = new PermissionRequest(
                                        de.augmentia.oc.engine.core.model.PermissionRequestId.create(),
                                        context.sessionId(),
                                        "external_directory",
                                        List.of(resolved.boundary().toString()),
                                        List.of(resolved.boundary().toString() + "/*"),
                                        Map.of("tool", "delete", "path", raw),
                                        null, null);
                                permissionGate.assertAllowed(req);
                            }
                        } catch (IllegalArgumentException ignored) {
                        }
                    }
                    return execute(fileSystem, access, value);
                },
                new ToolOptions(Option.none(), Option.of("edit"), true, false));
    }

    static ToolOutcome execute(FileSystem fileSystem, FileAccess access, JsonValue value) {
        try {
            String raw = JsonArgs.requiredString(value, "path");
            FileAccess.Resolved resolved = access.resolve(raw, false);
            FileSystem.FileStat stat;
            try {
                stat = fileSystem.stat(resolved.absolute());
            } catch (UncheckedIOException e) {
                return new ToolOutcome.Empty("File does not exist: " + resolved.absolute());
            }
            if (stat.directory()) {
                return ReadTool.failure("delete", "path is a directory");
            }
            fileSystem.delete(resolved.absolute());
            return new ToolOutcome.Ok(new ToolResult(Option.none(),
                    List.of(new ToolContent.TextItem("Deleted " + resolved.absolute())),
                    Map.of("files", List.of(Map.of(
                                    "path", resolved.absolute().toString(), "kind", "deleted")),
                            "path", resolved.absolute().toString(),
                            "deleted", true)));
        } catch (IllegalArgumentException e) {
            return ReadTool.failure("delete", e.getMessage());
        } catch (UncheckedIOException e) {
            return ReadTool.failure("delete", "cannot delete path");
        }
    }
}
