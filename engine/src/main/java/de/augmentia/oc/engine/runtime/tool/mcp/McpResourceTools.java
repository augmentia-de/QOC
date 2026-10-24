package de.augmentia.oc.engine.runtime.tool.mcp;

import de.augmentia.oc.engine.core.contract.tool.ToolDefinition;
import de.augmentia.oc.engine.core.contract.tool.ToolOptions;
import de.augmentia.oc.engine.core.contract.tool.ToolOutcome;
import de.augmentia.oc.engine.core.contract.tool.ToolResult;
import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.json.Schema;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.ToolContent;
import de.augmentia.oc.engine.core.model.ToolName;
import de.augmentia.oc.engine.runtime.tool.JsonArgs;
import de.augmentia.oc.engine.runtime.tool.fs.ReadTool;

import java.util.List;
import java.util.Map;

/** MCP resource tools: list resources/templates and read one resource by URI. */
public final class McpResourceTools {

    private McpResourceTools() {}

    public static ToolDefinition list(McpServerManager manager) {
        if (manager == null) throw new IllegalArgumentException("manager must not be null");
        Schema input = Schema.obj(Map.of("server", Schema.opt(Schema.str())));
        return new ToolDefinition(new ToolName("list_mcp_resources"),
                "List documents and other data exposed by MCP servers",
                input, Option.none(),
                (value, context) -> {
                    String server = JsonArgs.string(value, "server");
                    List<McpResource.Resource> resources = manager.resources().stream()
                            .filter(resource -> server == null || server.isBlank()
                                    || resource.server().equals(server))
                            .toList();
                    List<McpResource.Template> templates = manager.resourceTemplates().stream()
                            .filter(template -> server == null || server.isBlank()
                                    || template.server().equals(server))
                            .toList();
                    StringBuilder text = new StringBuilder();
                    for (McpResource.Resource resource : resources) {
                        text.append("- ").append(resource.server()).append(' ')
                                .append(resource.uri());
                        if (!resource.name().isBlank()) text.append(" (").append(resource.name()).append(')');
                        if (!resource.mimeType().isBlank()) text.append(" [").append(resource.mimeType()).append(']');
                        text.append('\n');
                    }
                    for (McpResource.Template template : templates) {
                        text.append("- ").append(template.server()).append(" template ")
                                .append(template.uriTemplate()).append('\n');
                    }
                    if (text.isEmpty()) text.append("No MCP resources.");
                    return new ToolOutcome.Ok(new ToolResult(Option.none(),
                            List.of(new ToolContent.TextItem(text.toString().strip())),
                            Map.of("resources", resources.size(), "templates", templates.size())));
                },
                new ToolOptions(Option.none(), Option.of("mcp"), true, false));
    }

    public static ToolDefinition read(McpServerManager manager) {
        if (manager == null) throw new IllegalArgumentException("manager must not be null");
        Schema input = Schema.obj(Map.of("server", Schema.str(), "uri", Schema.str()));
        return new ToolDefinition(new ToolName("read_mcp_resource"),
                "Read one MCP resource by server and URI",
                input, Option.none(),
                (value, context) -> {
                    try {
                        String server = JsonArgs.requiredString(value, "server");
                        String uri = JsonArgs.requiredString(value, "uri");
                        List<McpResource.Content> contents = manager.readResource(server, uri);
                        StringBuilder text = new StringBuilder();
                        for (McpResource.Content content : contents) {
                            text.append("URI: ").append(content.uri()).append('\n');
                            if (!content.mimeType().isBlank()) {
                                text.append("Type: ").append(content.mimeType()).append('\n');
                            }
                            if (!content.text().isBlank()) {
                                text.append('\n').append(content.text()).append('\n');
                            } else if (!content.blob().isBlank()) {
                                text.append("[binary content, ").append(content.blob().length())
                                        .append(" base64 chars]\n");
                            }
                        }
                        if (contents.isEmpty()) text.append("No content for ").append(uri);
                        return new ToolOutcome.Ok(new ToolResult(Option.none(),
                                List.of(new ToolContent.TextItem(text.toString().strip())),
                                Map.of("server", server, "uri", uri, "contents", contents.size())));
                    } catch (RuntimeException e) {
                        return ReadTool.failure("read_mcp_resource", e.getMessage());
                    }
                },
                new ToolOptions(Option.none(), Option.of("mcp"), true, false));
    }
}
