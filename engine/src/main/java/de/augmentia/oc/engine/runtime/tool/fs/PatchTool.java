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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** {@code patch}: applies a small unified diff to one file. */
public final class PatchTool {

    private static final Pattern HUNK = Pattern.compile("@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@.*");

    private PatchTool() {}

    public static ToolDefinition definition(FileSystem fileSystem, FileAccess access) {
        return definition(fileSystem, access, null);
    }

    public static ToolDefinition definition(FileSystem fileSystem, FileAccess access, PermissionGate permissionGate) {
        if (fileSystem == null || access == null) throw new IllegalArgumentException("dependencies must not be null");
        Schema input = Schema.obj(Map.of("path", Schema.str(), "patch", Schema.str()));
        return new ToolDefinition(new ToolName("patch"), "Apply a unified diff",
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
                                        Map.of("tool", "patch", "path", raw),
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
            String patch = JsonArgs.requiredString(value, "patch");
            FileAccess.Resolved resolved = access.resolve(raw, false);
            String content = fileSystem.readText(resolved.absolute());
            String patched = apply(content, patch);
            fileSystem.write(resolved.absolute(), patched.getBytes(StandardCharsets.UTF_8));

            JsonValue output = JsonValue.of(Map.of(
                    "operation", JsonValue.of("patch"),
                    "target", JsonValue.of(resolved.absolute().toString()),
                    "resource", JsonValue.of(resolved.resource())));
            return new ToolOutcome.Ok(new ToolResult(Option.some(output),
                    List.of(new ToolContent.TextItem("Patched " + resolved.absolute())),
                    Map.of("files", List.of(Map.of(
                            "path", resolved.absolute().toString(), "kind", "modified")))));
        } catch (IllegalArgumentException e) {
            return ReadTool.failure("patch", e.getMessage());
        } catch (UncheckedIOException e) {
            return ReadTool.failure("patch", "cannot patch path");
        }
    }

    static String apply(String content, String patch) {
        List<String> lines = new ArrayList<>(List.of(content.split("\n", -1)));
        int offset = 0;
        int index = 0;
        String[] patchLines = patch.split("\n", -1);
        while (index < patchLines.length) {
            Matcher matcher = HUNK.matcher(patchLines[index]);
            if (!matcher.matches()) {
                index++;
                continue;
            }
            int oldStart = Integer.parseInt(matcher.group(1));
            List<String> oldLines = new ArrayList<>();
            List<String> newLines = new ArrayList<>();
            index++;
            while (index < patchLines.length && !patchLines[index].startsWith("@@")) {
                String line = patchLines[index];
                if (line.startsWith(" ")) {
                    oldLines.add(line.substring(1));
                    newLines.add(line.substring(1));
                } else if (line.startsWith("-")) {
                    oldLines.add(line.substring(1));
                } else if (line.startsWith("+")) {
                    newLines.add(line.substring(1));
                } else if (line.startsWith("\\")) {
                    // "\ No newline at end of file" is ignored.
                } else if (!line.isBlank()) {
                    throw new IllegalArgumentException("invalid patch line: " + line);
                }
                index++;
            }
            int at = oldStart - 1 + offset;
            if (at < 0 || at + oldLines.size() > lines.size()) {
                throw new IllegalArgumentException("patch hunk outside the file");
            }
            for (int i = 0; i < oldLines.size(); i++) {
                if (!lines.get(at + i).equals(oldLines.get(i))) {
                    throw new IllegalArgumentException("patch context does not match");
                }
            }
            lines.subList(at, at + oldLines.size()).clear();
            lines.addAll(at, newLines);
            offset += newLines.size() - oldLines.size();
        }
        return String.join("\n", lines);
    }
}
