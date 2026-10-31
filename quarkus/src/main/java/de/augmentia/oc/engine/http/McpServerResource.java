package de.augmentia.oc.engine.http;

import de.augmentia.oc.engine.core.json.JsonSchema;
import de.augmentia.oc.engine.core.json.JsonValues;
import de.augmentia.oc.engine.core.json.Result;
import de.augmentia.oc.engine.core.json.Schema;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.SessionId;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import de.augmentia.oc.engine.http.security.PrincipalContext;
import de.augmentia.oc.engine.http.security.SecurityConfig;
import de.augmentia.oc.engine.http.security.AuthMode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

@Path("/mcp")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class McpServerResource {
    private static final Logger LOG = Logger.getLogger(McpServerResource.class.getName());

    @Inject
    Engine engine;

    @Inject
    @ConfigProperty(name = "engine.mcp.server.enabled", defaultValue = "true")
    boolean enabled;

    @Inject
    @ConfigProperty(name = "engine.mcp.server.oauth.enabled", defaultValue = "false")
    boolean oauthEnabled;

    @Inject
    SecurityConfig securityConfig;

    @Inject
    PrincipalContext principalContext;

    @POST
    public Response handle(Map<String, Object> request) {
        if (!enabled) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(Map.of("error", "MCP server is disabled")).build();
        }
        if (oauthEnabled || (securityConfig != null && securityConfig.mode() == AuthMode.MCP_OAUTH)) {
            if (principalContext == null || principalContext.get() == null) {
                LOG.log(Level.WARNING, "security.mcp_unauthorized");
                return Response.status(Response.Status.UNAUTHORIZED)
                        .entity(Map.of("error", "unauthorized"))
                        .header("WWW-Authenticate", "Bearer")
                        .build();
            }
        }
        Object id = request == null ? null : request.get("id");
        String method = request == null ? null : String.valueOf(request.get("method"));
        Map<String, Object> params = request == null || !(request.get("params") instanceof Map)
                ? Map.of() : (Map<String, Object>) request.get("params");

        try {
            Map<String, Object> result = switch (method == null ? "" : method) {
                case "initialize" -> initialize();
                case "tools/list" -> toolsList();
                case "tools/call" -> toolsCall(params);
                default -> throw new IllegalArgumentException("unknown method: " + method);
            };
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("jsonrpc", "2.0");
            response.put("id", id);
            response.put("result", result);
            return Response.ok(response).build();
        } catch (Exception e) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("code", -32000);
            error.put("message", e.getMessage() == null ? "MCP error" : e.getMessage());
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("jsonrpc", "2.0");
            response.put("id", id);
            response.put("error", error);
            return Response.ok(response).build();
        }
    }

    private Map<String, Object> initialize() {
        Map<String, Object> serverInfo = new LinkedHashMap<>();
        serverInfo.put("name", "qoc");
        serverInfo.put("version", "1.0.0");

        Map<String, Object> capabilities = new LinkedHashMap<>();
        capabilities.put("tools", Map.of());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("protocolVersion", "2024-11-05");
        result.put("capabilities", capabilities);
        result.put("serverInfo", serverInfo);
        return result;
    }

    private Map<String, Object> toolsList() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("agent", Map.of("type", "string", "description", "Agent name or id"));
        properties.put("prompt", Map.of("type", "string", "description", "Task prompt"));
        properties.put("workspace", Map.of("type", "string", "description", "Optional workspace subdirectory"));
        properties.put("timeoutSeconds", Map.of("type", "integer", "description", "Optional timeout"));
        properties.put("sessionMode", Map.of("type", "string", "enum", List.of("new", "reuse"),
                "description", "Create a new session or reuse an existing one"));
        properties.put("sessionId", Map.of("type", "string", "description", "Existing session id for reuse"));
        properties.put("structuredOutput", Map.of("type", "object", "description",
                "Optional JSON schema for structured output"));
        properties.put("maxSteps", Map.of("type", "integer", "description", "Optional step limit"));
        properties.put("mode", Map.of("type", "string", "enum", List.of("workspace", "ephemeral"),
                "description", "Execution mode: workspace (persistent) or ephemeral (one-shot, cleaned up)"));
        properties.put("retain", Map.of("type", "boolean",
                "description", "Keep an ephemeral session after completion for inspection"));
        schema.put("properties", properties);
        schema.put("required", List.of("prompt"));

        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("name", "qoc_run_agent");
        tool.put("description", "Run a QOC agent task and return its result.");
        tool.put("inputSchema", schema);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tools", List.of(tool));
        return result;
    }

    @GET
    @Path("/authorize")
    public Response authorize() {
        if (!oauthEnabled && (securityConfig == null || securityConfig.mode() != AuthMode.MCP_OAUTH)) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(Map.of("error", "OAuth is disabled")).build();
        }
        return Response.status(Response.Status.NOT_IMPLEMENTED)
                .entity(Map.of("error", "OAuth authorization is not implemented yet")).build();
    }

    @GET
    @Path("/callback")
    public Response callback(@QueryParam("code") String code,
                            @QueryParam("state") String state) {
        if (!oauthEnabled && (securityConfig == null || securityConfig.mode() != AuthMode.MCP_OAUTH)) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(Map.of("error", "OAuth is disabled")).build();
        }
        return Response.status(Response.Status.NOT_IMPLEMENTED)
                .entity(Map.of("error", "OAuth callback is not implemented yet")).build();
    }

    private Map<String, Object> toolsCall(Map<String, Object> params) {
        String name = String.valueOf(params.getOrDefault("name", ""));
        if (!"qoc_run_agent".equals(name)) {
            throw new IllegalArgumentException("unknown tool: " + name);
        }
        Map<String, Object> arguments = params.get("arguments") instanceof Map
                ? (Map<String, Object>) params.get("arguments") : Map.of();

        String agent = stringArg(arguments, "agent", null);
        String prompt = stringArg(arguments, "prompt", null);
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("prompt is required");
        }
        String user = "opencode";
        String sessionId = stringArg(arguments, "sessionId", null);
        String sessionMode = stringArg(arguments, "sessionMode", "new");
        Object structuredSchema = arguments.get("structuredOutput");
        Integer maxSteps = intArg(arguments, "maxSteps");
        String mode = stringArg(arguments, "mode", "workspace");
        boolean retain = boolArg(arguments, "retain", false);
        String workspaceSubdir = stringArg(arguments, "workspace", null);

        SessionId session;
        if ("reuse".equalsIgnoreCase(sessionMode) && sessionId != null && !sessionId.isBlank()) {
            session = SessionId.parse(sessionId);
        } else {
            session = engine.createSession(agent, user, mode, retain);
        }

        Option<Schema> responseSchema = Option.none();
        if (structuredSchema instanceof Map) {
            Result<Schema> parsed = JsonSchema.parse(JsonValues.fromJava(structuredSchema));
            if (parsed instanceof Result.Err<Schema> error) {
                throw new IllegalArgumentException("invalid structuredOutput at " + error.error().path()
                        + ": " + error.error().message());
            }
            responseSchema = Option.some(parsed.valueOrNull());
        }

        Engine.PromptResult result = engine.prompt(session, prompt, responseSchema);

        Map<String, Object> contentItem = new LinkedHashMap<>();
        contentItem.put("type", "text");
        contentItem.put("text", result.text());

        Map<String, Object> toolResult = new LinkedHashMap<>();
        toolResult.put("content", List.of(contentItem));
        toolResult.put("isError", false);

        Map<String, Object> structured = result.structured()
                .map(JsonValues::toJava).map(v -> (Map<String, Object>) v).orElse(null);
        if (structured != null) {
            toolResult.put("structuredContent", structured);
        }

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("sessionId", session.value());
        meta.put("finish", result.finish());
        meta.put("inputTokens", result.inputTokens());
        meta.put("outputTokens", result.outputTokens());
        toolResult.put("_meta", meta);

        return toolResult;
    }

    private static String stringArg(Map<String, Object> args, String key, String defaultValue) {
        Object value = args.get(key);
        return value == null ? defaultValue : String.valueOf(value);
    }

    private static Integer intArg(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value == null) return null;
        if (value instanceof Number number) return number.intValue();
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean boolArg(Map<String, Object> args, String key, boolean defaultValue) {
        Object value = args.get(key);
        if (value == null) return defaultValue;
        if (value instanceof Boolean b) return b;
        return Boolean.parseBoolean(String.valueOf(value));
    }
}
