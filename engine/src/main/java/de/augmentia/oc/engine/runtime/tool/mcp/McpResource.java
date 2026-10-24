package de.augmentia.oc.engine.runtime.tool.mcp;

import java.util.List;

/** MCP resources and resource templates (read-only documents). */
public final class McpResource {

    public record Resource(String server, String uri, String name, String description, String mimeType) {
        public Resource {
            if (server == null) server = "";
            if (uri == null) uri = "";
            if (name == null) name = "";
            if (description == null) description = "";
            if (mimeType == null) mimeType = "";
        }
    }

    public record Template(String server, String uriTemplate, String name, String description, String mimeType) {
        public Template {
            if (server == null) server = "";
            if (uriTemplate == null) uriTemplate = "";
            if (name == null) name = "";
            if (description == null) description = "";
            if (mimeType == null) mimeType = "";
        }
    }

    public record Content(String uri, String mimeType, String text, String blob) {
        public Content {
            if (uri == null) uri = "";
            if (mimeType == null) mimeType = "";
            if (text == null) text = "";
            if (blob == null) blob = "";
        }
    }

    private McpResource() {}
}
