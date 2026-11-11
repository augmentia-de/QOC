package de.augmentia.oc.engine.http;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.LinkedHashMap;
import java.util.Map;

/** MCP status/authorization endpoints extracted from {@link HttpApi} (H7). */
@Path("/api")
@Produces(MediaType.APPLICATION_JSON)
public class McpResource {

    @Inject
    Engine engine;

    @GET
    @Path("/mcp")
    public Response mcpServers() {
        return Response.ok(Map.of("data", engine.mcpServerNames())).build();
    }

    @GET
    @Path("/mcp/{server}/status")
    public Response mcpStatus(@PathParam("server") String server) {
        String error = engine.mcpError(server);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", engine.mcpStatus(server));
        if (error != null && !error.isBlank()) body.put("error", error);
        return Response.ok(body).build();
    }

    @GET
    @Path("/mcp/{server}/authorize")
    public Response mcpAuthorize(@PathParam("server") String server,
                                 @QueryParam("clientId") String clientId,
                                 @QueryParam("redirectUri") String redirectUri) {
        try {
            String url = engine.mcpAuthorizationUrl(server, clientId, redirectUri);
            if (url == null) return Response.status(400)
                    .entity(Map.of("error", "server is not a remote discovery client")).build();
            return Response.ok(Map.of("url", url)).build();
        } catch (Exception e) {
            return Response.status(500).entity(Map.of("error", e.getMessage())).build();
        }
    }

    @GET
    @Path("/mcp/{server}/callback")
    public Response mcpCallback(@PathParam("server") String server,
                                @QueryParam("code") String code,
                                @QueryParam("redirectUri") String redirectUri,
                                @QueryParam("clientId") String clientId,
                                @QueryParam("clientSecret") String clientSecret) {
        if (code == null || code.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "code is required")).build();
        }
        try {
            engine.mcpAuthenticate(server, code, redirectUri, clientId, clientSecret);
            return Response.ok(Map.of("status", "connected")).build();
        } catch (Exception e) {
            return Response.status(400).entity(Map.of("error", e.getMessage())).build();
        }
    }
}
