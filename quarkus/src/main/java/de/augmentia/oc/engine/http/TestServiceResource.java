package de.augmentia.oc.engine.http;

import de.augmentia.oc.engine.http.security.PrincipalContext;
import de.augmentia.oc.engine.http.security.SecurityConfig;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Kleiner Test-Service: stellt einen minimalen MCP-Server (Tool "add" = +1)
 * und einen REST-Endpunkt (Dekrement = -1) bereit.
 *
 * <p>Wenn OIDC/Security aktiv ist, sind beide Endpunkte geschützt.</p>
 */
@Path("/api/test")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class TestServiceResource {

    @Inject
    SecurityConfig securityConfig;

    @Inject
    PrincipalContext principalContext;

    private Response unauthorized() {
        boolean required = securityConfig != null && securityConfig.authenticationRequired();
        if (!required) return null;
        if (principalContext == null || principalContext.get() == null
                || "anonymous".equals(principalContext.get().id())) {
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity(Map.of("error", "unauthorized"))
                    .header("WWW-Authenticate", "Bearer")
                    .build();
        }
        return null;
    }

    @POST
    @Path("/mcp")
    public Response mcp(Map<String, Object> request) {
        Response auth = unauthorized();
        if (auth != null) return auth;

        Object id = request == null ? null : request.get("id");
        String method = request == null ? null : String.valueOf(request.get("method"));
        Map<String, Object> params = request != null && request.get("params") instanceof Map
                ? (Map<String, Object>) request.get("params") : Map.of();

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
            error.put("message", e.getMessage() == null ? "error" : e.getMessage());
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("jsonrpc", "2.0");
            response.put("id", id);
            response.put("error", error);
            return Response.ok(response).build();
        }
    }

    private Map<String, Object> initialize() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("protocolVersion", "2024-11-05");
        result.put("capabilities", Map.of("tools", Map.of()));
        result.put("serverInfo", Map.of("name", "qoc-test-service", "version", "1.0.0"));
        return result;
    }

    private Map<String, Object> toolsList() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of("value", Map.of("type", "integer")));
        schema.put("required", List.of("value"));

        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("name", "add");
        tool.put("description", "Add +1 to the given value.");
        tool.put("inputSchema", schema);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tools", List.of(tool));
        return result;
    }

    private Map<String, Object> toolsCall(Map<String, Object> params) {
        String name = String.valueOf(params.getOrDefault("name", ""));
        if (!"add".equals(name)) {
            throw new IllegalArgumentException("unknown tool: " + name);
        }
        Map<String, Object> arguments = params.get("arguments") instanceof Map
                ? (Map<String, Object>) params.get("arguments") : Map.of();
        int value = 0;
        Object raw = arguments.get("value");
        if (raw instanceof Number n) value = n.intValue();
        else if (raw != null) value = Integer.parseInt(String.valueOf(raw));

        Map<String, Object> content = new LinkedHashMap<>();
        content.put("type", "text");
        content.put("text", String.valueOf(value + 1));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", List.of(content));
        result.put("value", value + 1);
        return result;
    }

    @POST
    @Path("/decrement")
    public Response decrement(Map<String, Object> body) {
        Response auth = unauthorized();
        if (auth != null) return auth;

        int value = 0;
        if (body != null && body.get("value") instanceof Number n) value = n.intValue();
        else if (body != null && body.get("value") != null) value = Integer.parseInt(String.valueOf(body.get("value")));

        return Response.ok(Map.of("result", value - 1)).build();
    }

    @GET
    @Path("/decrement")
    public Response decrementGet(@QueryParam("value") Integer value) {
        Response auth = unauthorized();
        if (auth != null) return auth;
        int v = value == null ? 0 : value;
        return Response.ok(Map.of("result", v - 1)).build();
    }
}
