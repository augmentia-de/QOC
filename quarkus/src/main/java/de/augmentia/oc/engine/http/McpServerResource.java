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
            if (principalContext == null || principalContext.get() == null
                    || "anonymous".equals(principalContext.get().id())) {
                LOG.log(Level.WARNING, "security.mcp_unauthorized");
                return Response.status(Response.Status.UNAUTHORIZED)
                        .entity(Map.of("error", "unauthorized"))
                        .header("WWW-Authenticate", "Bearer")
                        .build();
            }
        }
        Object id = request == null ? null : request.get("id");
        String method = request == null ? null : String.valueOf(request.get("method"));
        if (engine.isRegistryMode() && "tools/call".equals(method)) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("code", -32001);
            error.put("message", "execution is disabled in registry role");
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("jsonrpc", "2.0");
            response.put("id", id);
            response.put("error", error);
            return Response.status(Response.Status.SERVICE_UNAVAILABLE).entity(response).build();
        }
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

        // qoc_run_agent_with_config schema
        Map<String, Object> configSchema = new LinkedHashMap<>(schema);
        Map<String, Object> configProps = new LinkedHashMap<>(properties);
        configProps.put("agentConfig", Map.of("type", "string",
                "description", "Agent definition (Markdown with frontmatter) or JSON object"));
        configSchema.put("properties", configProps);
        configSchema.put("required", List.of("agentConfig", "prompt"));

        Map<String, Object> configTool = new LinkedHashMap<>();
        configTool.put("name", "qoc_run_agent_with_config");
        configTool.put("description", "Run a stateless QOC subagent with an inline agent configuration. "
                + "The agent is created temporarily, the prompt is executed, and the agent is deleted afterwards.");
        configTool.put("inputSchema", configSchema);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tools", List.of(tool, configTool));
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
        if (!"qoc_run_agent".equals(name) && !"qoc_run_agent_with_config".equals(name)) {
            throw new IllegalArgumentException("unknown tool: " + name);
        }
        Map<String, Object> arguments = params.get("arguments") instanceof Map
                ? (Map<String, Object>) params.get("arguments") : Map.of();

        String prompt = stringArg(arguments, "prompt", null);
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("prompt is required");
        }
        String user = "opencode";
        if (principalContext != null && principalContext.get() != null) {
            String pid = principalContext.get().id();
            if (pid != null && !pid.isBlank() && !"anonymous".equals(pid)) {
                user = pid;
            }
        }

        // Inline agents are materialised in memory only, so serverless/Lambda
        // instances never need a writable agent directory.
        String tempAgentName = null;
        String transientAgentName = null;
        boolean agentCreated = false;
        if ("qoc_run_agent_with_config".equals(name)) {
            Object agentConfig = arguments.get("agentConfig");
            if (agentConfig == null) {
                throw new IllegalArgumentException("agentConfig is required");
            }
            String configText = agentConfig instanceof String s ? s : String.valueOf(agentConfig);
            if (configText.isBlank()) {
                throw new IllegalArgumentException("agentConfig must not be blank");
            }
            if (configText.length() > 65536) {
                throw new IllegalArgumentException("agentConfig exceeds maximum size (64 KiB)");
            }
            tempAgentName = "mcp-tmp-" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            EngineConfig.AgentConfig cfg = AgentMarkdownLoader.parseContent(configText, tempAgentName);
            if (cfg == null) {
                throw new IllegalArgumentException(
                        "agentConfig must be a Markdown agent definition with frontmatter");
            }
            if (!AgentAccess.allowed(cfg, currentRoles())) {
                throw new IllegalArgumentException("agent not allowed: inline configuration");
            }
            engine.defineTransientAgent(tempAgentName, cfg, null);
            transientAgentName = tempAgentName;
            agentCreated = true;
        }

        String agent = stringArg(arguments, "agent", null);

        // Serverless: fetch the agent definition on demand for exactly this
        // call, then remove it again in the finally block below.
        if ("qoc_run_agent".equals(name) && agent != null && !agent.isBlank()
                && engine.isServerlessMode() && !engine.hasAgent(agent)) {
            String callerToken = principalContext != null && principalContext.get() != null
                    ? principalContext.get().accessToken() : null;
            java.util.Map<String, Object> fetched = engine.fetchAgentConfig(agent, callerToken);
            if (fetched == null) {
                throw new IllegalArgumentException("agent not available: " + agent);
            }
            engine.defineTransientAgent(agent, fetched);
            transientAgentName = agent;
            agentCreated = true;
        }

        if (tempAgentName != null) {
            agent = tempAgentName;
        }
        if (agent != null && !agent.isBlank() && !agentAllowed(agent)) {
            if (transientAgentName != null) {
                try {
                    engine.removeTransientAgent(transientAgentName);
                } catch (RuntimeException ignored) {
                }
                transientAgentName = null;
            }
            throw new IllegalArgumentException("agent not allowed: " + agent);
        }
        String sessionId = stringArg(arguments, "sessionId", null);
        String sessionMode = stringArg(arguments, "sessionMode", "new");
        Object structuredSchema = arguments.get("structuredOutput");
        Integer maxSteps = intArg(arguments, "maxSteps");
        String mode = stringArg(arguments, "mode", "workspace");
        boolean retain = boolArg(arguments, "retain", false);

        SessionId session;
        Engine.PromptResult promptResult = null;
        boolean agentDeleted = false;
        try {
            if ("reuse".equalsIgnoreCase(sessionMode) && sessionId != null && !sessionId.isBlank()) {
                session = SessionId.parse(sessionId);
                engine.session(session).ifPresent(info -> {
                    String existing = engine.agentName(info.agent());
                    if (!agentAllowed(existing)) {
                        throw new IllegalArgumentException("agent not allowed: " + existing);
                    }
                });
            } else {
                session = engine.createSession(agent, user, mode, retain, currentRoles());
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

            promptResult = engine.prompt(session, prompt, responseSchema);

        } finally {
            if (transientAgentName != null) {
                try {
                    engine.removeTransientAgent(transientAgentName);
                    agentDeleted = true;
                } catch (Exception ignored) {
                }
            }
        }

        if (promptResult == null) {
            throw new IllegalStateException("prompt did not produce a result");
        }

        Map<String, Object> contentItem = new LinkedHashMap<>();
        contentItem.put("type", "text");
        contentItem.put("text", promptResult.text());

        Map<String, Object> toolResult = new LinkedHashMap<>();
        toolResult.put("content", List.of(contentItem));
        toolResult.put("isError", false);

        promptResult.structured()
                .map(JsonValues::toJava)
                .map(v -> (Map<String, Object>) v)
                .ifPresent(s -> toolResult.put("structuredContent", s));

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("sessionId", session.value());
        meta.put("finish", promptResult.finish());
        meta.put("inputTokens", promptResult.inputTokens());
        meta.put("outputTokens", promptResult.outputTokens());
        meta.put("agentCreated", agentCreated);
        meta.put("agentDeleted", agentDeleted);
        toolResult.put("_meta", meta);

        return toolResult;
    }

    private List<String> currentRoles() {
        if (principalContext == null) return List.of();
        var principal = principalContext.get();
        if (principal == null || principal.roles() == null) return List.of();
        return principal.roles();
    }

    private boolean agentAllowed(String name) {
        return engine.agentAllowed(name, currentRoles());
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
