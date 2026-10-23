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
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** {@code write}: action {@code edit}, no byte content in the event log. */
public final class WriteTool {

    private WriteTool() {}

    public static ToolDefinition definition(FileSystem fileSystem, FileAccess access) {
        return definition(fileSystem, access, null);
    }

    public static ToolDefinition definition(FileSystem fileSystem, FileAccess access, PermissionGate permissionGate) {
        if (fileSystem == null || access == null) throw new IllegalArgumentException("dependencies must not be null");
        Schema input = Schema.obj(Map.of("path", Schema.str(), "content", Schema.str()));
        return new ToolDefinition(new ToolName("write"), "Write a file",
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
                                        Map.of("tool", "write", "path", raw),
                                        null, null);
                                permissionGate.assertAllowed(req);
                            }
                        } catch (IllegalArgumentException ignored) {
                        }
                    }
                    return execute(fileSystem, access, value);
                }, null);
    }

    static ToolOutcome execute(FileSystem fileSystem, FileAccess access, JsonValue value) {
        try {
            String raw = JsonArgs.requiredString(value, "path");
            String content = JsonArgs.string(value, "content");
            if (content == null) content = "";
            FileAccess.Resolved resolved = access.resolve(raw, false);

            boolean existed = exists(fileSystem, resolved);
            fileSystem.write(resolved.absolute(), content.getBytes(StandardCharsets.UTF_8));

            JsonValue output = JsonValue.of(Map.of(
                    "operation", JsonValue.of("write"),
                    "target", JsonValue.of(resolved.absolute().toString()),
                    "resource", JsonValue.of(resolved.resource()),
                    "existed", JsonValue.of(existed)));
            String text = (existed ? "Updated " : "Created ") + resolved.absolute();
            Map<String, Object> metadata = Map.of("files", List.of(Map.of(
                    "path", resolved.absolute().toString(),
                    "kind", existed ? "modified" : "added")));
            return new ToolOutcome.Ok(new ToolResult(Option.some(output),
                    List.of(new ToolContent.TextItem(text)), metadata));
        } catch (IllegalArgumentException e) {
            return ReadTool.failure("write", e.getMessage());
        } catch (UncheckedIOException e) {
            return ReadTool.failure("write", "cannot write path");
        }
    }

    private static boolean exists(FileSystem fileSystem, FileAccess.Resolved resolved) {
        try {
            fileSystem.stat(resolved.absolute());
            return true;
        } catch (UncheckedIOException e) {
            return false;
        }
    }
}
