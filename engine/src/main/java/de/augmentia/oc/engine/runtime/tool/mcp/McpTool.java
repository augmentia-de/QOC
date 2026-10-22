package de.augmentia.oc.engine.runtime.tool.mcp;

import de.augmentia.oc.engine.core.contract.tool.ToolDefinition;
import de.augmentia.oc.engine.core.contract.tool.ToolOptions;
import de.augmentia.oc.engine.core.contract.tool.ToolOutcome;
import de.augmentia.oc.engine.core.contract.tool.ToolResult;
import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.json.Schema;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.SessionError;
import de.augmentia.oc.engine.core.model.ToolContent;
import de.augmentia.oc.engine.core.model.ToolName;
import de.augmentia.oc.engine.runtime.tool.fs.ReadTool;

import java.util.List;
import java.util.Map;

/** One MCP tool: namespaced name, action identical to the name (W-70/W-71). */
public final class McpTool {

    private McpTool() {}

    public static ToolDefinition definition(String server, String tool, String description,
                                            Schema inputSchema, McpClient client) {
        if (server == null || server.isBlank()) throw new IllegalArgumentException("server must not be blank");
        if (tool == null || tool.isBlank()) throw new IllegalArgumentException("tool must not be blank");
        if (inputSchema == null) throw new IllegalArgumentException("inputSchema must not be null");
        if (client == null) throw new IllegalArgumentException("client must not be null");

        String effectiveName = server + "_" + tool;
        ToolOptions options = new ToolOptions(Option.some(server), Option.some(effectiveName), true, false);
        return new ToolDefinition(new ToolName(tool), description == null ? "" : description,
                inputSchema, Option.none(),
                (value, context) -> execute(client, tool, value), options);
    }

    static ToolOutcome execute(McpClient client, String tool, JsonValue arguments) {
        try {
            JsonValue result = client.call(tool, arguments);
            McpContentMapper mapper = new McpContentMapper();
            List<ToolContent> content = mapper.map(result);
            if (mapper.isError(result)) {
                String message = content.stream()
                        .filter(item -> item instanceof ToolContent.TextItem)
                        .map(item -> ((ToolContent.TextItem) item).text())
                        .findFirst()
                        .orElse("MCP tool failed");
                return new ToolOutcome.Failed(new SessionError.ToolExecutionError(tool, message));
            }
            return new ToolOutcome.Ok(new ToolResult(Option.none(), content, Map.of()));
        } catch (McpFailure e) {
            return ReadTool.failure(tool, "MCP call failed: " + e.getMessage());
        }
    }
}
