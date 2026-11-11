package de.augmentia.oc.engine.runtime.tool.remote;

import de.augmentia.oc.engine.core.contract.tool.ToolDefinition;
import de.augmentia.oc.engine.core.contract.tool.ToolOptions;
import de.augmentia.oc.engine.core.contract.tool.ToolOutcome;
import de.augmentia.oc.engine.core.contract.tool.ToolResult;
import de.augmentia.oc.engine.core.json.Json;
import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.json.Schema;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.ToolContent;
import de.augmentia.oc.engine.core.model.SessionError;
import de.augmentia.oc.engine.core.model.ToolName;
import de.augmentia.oc.engine.core.port.world.RemoteShell;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * {@code remote}: SSH-based remote shell and file operations.
 * V1 supports exec, read, write, list, stat. Download/upload are not yet
 * implemented.
 */
public final class RemoteTool {

    private static final Logger LOG = Logger.getLogger(RemoteTool.class.getName());

    private RemoteTool() {
    }

    public static ToolDefinition definition(RemoteShell remote, RemoteShell.HostConfig host) {
        if (remote == null || host == null) {
            return null; // tool not registered when no host configured
        }
        Map<String, Schema> props = new LinkedHashMap<>();
        props.put("host", Schema.string());
        props.put("action", Schema.enm(List.of("exec", "read", "write", "list", "stat")));
        props.put("command", Schema.string());
        props.put("path", Schema.string());
        props.put("content", Schema.string());
        props.put("encoding", Schema.string());
        props.put("cwd", Schema.string());
        props.put("timeoutMs", Schema.integer());
        props.put("maxBytes", Schema.integer());

        Schema schema = Schema.object(props, false);

        String name = "remote";
        String description = "Execute commands and file operations on a remote host via SSH. "
                + "Host: " + host.alias() + ". Actions: exec, read, write, list, stat.";

        return new ToolDefinition(new ToolName(name), description, schema, Option.none(),
                (input, context) -> execute(remote, host, input), ToolOptions.defaults());
    }

    private static ToolOutcome execute(RemoteShell remote, RemoteShell.HostConfig host, JsonValue input) {
        try {
            if (!(input instanceof JsonValue.Obj obj)) {
                return failure("invalid_input", "input must be an object");
            }
            Map<String, JsonValue> v = obj.values();
            String action = stringVal(v, "action");
            if (action == null || action.isBlank()) {
                return failure("invalid_input", "action is required");
            }

            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("host", host.alias());
            meta.put("action", action);

            switch (action) {
                case "exec" -> {
                    String command = stringVal(v, "command");
                    if (command == null || command.isBlank()) {
                        return failure("invalid_input", "command is required for exec");
                    }
                    String cwd = stringVal(v, "cwd");
                    int timeoutMs = intVal(v, "timeoutMs", 10000);
                    var result = remote.exec(host, command,
                            cwd != null && !cwd.isBlank() ? Option.some(cwd) : Option.none(),
                            java.time.Duration.ofMillis(timeoutMs));
                    meta.put("exitCode", result.exitCode());
                    meta.put("durationMs", result.durationMs());
                    meta.put("timedOut", result.timedOut());
                    List<ToolContent> content = new ArrayList<>();
                    if (!result.stdout().isBlank()) content.add(new ToolContent.TextItem("stdout:\n" + result.stdout()));
                    if (!result.stderr().isBlank()) content.add(new ToolContent.TextItem("stderr:\n" + result.stderr()));
                    if (content.isEmpty()) content.add(new ToolContent.TextItem("(no output)"));
                    return new ToolOutcome.Ok(new ToolResult(Option.none(), content, meta));
                }
                case "read" -> {
                    String path = stringVal(v, "path");
                    if (path == null || path.isBlank()) return failure("invalid_input", "path is required for read");
                    int maxBytes = intVal(v, "maxBytes", host.maxFileBytes());
                    var result = remote.read(host, path, maxBytes);
                    meta.put("path", result.path());
                    meta.put("bytes", result.bytes());
                    meta.put("truncated", result.truncated());
                    List<ToolContent> content = new ArrayList<>();
                    content.add(new ToolContent.TextItem(result.content()));
                    return new ToolOutcome.Ok(new ToolResult(Option.none(), content, meta));
                }
                case "write" -> {
                    String path = stringVal(v, "path");
                    String content = stringVal(v, "content");
                    if (path == null || path.isBlank()) return failure("invalid_input", "path is required for write");
                    if (content == null) return failure("invalid_input", "content is required for write");
                    String encoding = stringVal(v, "encoding");
                    if (encoding == null || encoding.isBlank()) encoding = "utf-8";
                    var result = remote.write(host, path, content, encoding);
                    meta.put("path", result.path());
                    meta.put("bytesWritten", result.bytesWritten());
                    List<ToolContent> content2 = new ArrayList<>();
                    content2.add(new ToolContent.TextItem("written " + result.bytesWritten() + " bytes to " + result.path()));
                    return new ToolOutcome.Ok(new ToolResult(Option.none(), content2, meta));
                }
                case "list" -> {
                    String path = stringVal(v, "path");
                    if (path == null || path.isBlank()) return failure("invalid_input", "path is required for list");
                    int maxEntries = intVal(v, "maxBytes", host.maxEntries());
                    var result = remote.list(host, path, maxEntries);
                    meta.put("path", result.path());
                    meta.put("truncated", result.truncated());
                    List<ToolContent> content = new ArrayList<>();
                    StringBuilder sb = new StringBuilder();
                    for (var e : result.entries()) {
                        sb.append(e.type()).append(" ").append(e.name()).append(" ").append(e.size()).append("\n");
                    }
                    content.add(new ToolContent.TextItem(sb.toString()));
                    return new ToolOutcome.Ok(new ToolResult(Option.none(), content, meta));
                }
                case "stat" -> {
                    String path = stringVal(v, "path");
                    if (path == null || path.isBlank()) return failure("invalid_input", "path is required for stat");
                    var result = remote.stat(host, path);
                    meta.put("path", result.path());
                    meta.put("type", result.type());
                    meta.put("size", result.size());
                    meta.put("mode", result.mode());
                    List<ToolContent> content = new ArrayList<>();
                    content.add(new ToolContent.TextItem(result.type() + " " + result.path() + " size=" + result.size() + " mode=" + result.mode()));
                    return new ToolOutcome.Ok(new ToolResult(Option.none(), content, meta));
                }
                default -> {
                    return failure("invalid_action", "unknown action: " + action);
                }
            }
        } catch (Exception e) {
            LOG.warning("remote tool failed: " + e.getMessage());
            return failure("remote_error", e.getMessage() == null ? "unknown error" : e.getMessage());
        }
    }

    private static String stringVal(Map<String, JsonValue> v, String key) {
        JsonValue val = v.get(key);
        if (val instanceof JsonValue.Str s) return s.value();
        if (val != null) return String.valueOf(val);
        return null;
    }

    private static int intVal(Map<String, JsonValue> v, String key, int defaultVal) {
        JsonValue val = v.get(key);
        if (val instanceof JsonValue.Num n) return n.value().intValue();
        if (val != null) {
            try { return Integer.parseInt(String.valueOf(val)); }
            catch (NumberFormatException ignored) { }
        }
        return defaultVal;
    }

    private static ToolOutcome failure(String tag, String message) {
        return new ToolOutcome.Failed(new SessionError.ToolExecutionError(tag, message));
    }
}
