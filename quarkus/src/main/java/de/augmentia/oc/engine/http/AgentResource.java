package de.augmentia.oc.engine.http;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Agent catalogue/authoring endpoints extracted from {@link HttpApi} (H7). */
@Path("/api")
@Produces(MediaType.APPLICATION_JSON)
public class AgentResource {

    @Inject
    Engine engine;

    @Inject
    SessionAccessGuard accessGuard;

    @GET
    @Path("/agent")
    public Response agents() {
        List<AgentCatalogue.AgentDto> visible = engine.agents().stream()
                .filter(agent -> agentAllowed(agent.name()))
                .toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("location", engine.location());
        body.put("data", visible);
        body.put("canAuthor", accessGuard.isAdmin());
        return Response.ok(body).build();
    }

    @GET
    @Path("/agent/{name}")
    public Response agent(@PathParam("name") String name) {
        if (name == null || name.isBlank()) {
            return badRequest("agent name must not be blank");
        }
        if (!agentAllowed(name)) {
            return agentForbidden(name);
        }
        try {
            String content = engine.agentContent(name);
            de.augmentia.oc.engine.core.json.JsonValue.Obj agentConfig = engine.agentConfig(name);
            if (content == null && agentConfig == null) {
                return Response.status(Response.Status.NOT_FOUND).build();
            }
            String hash = engine.agentHash(name);
            String updatedAt = engine.agentUpdatedAt(name);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("name", name);
            if (content != null) body.put("content", content);
            if (agentConfig != null) {
                body.put("config",
                        de.augmentia.oc.engine.core.json.JsonValues.toJava(agentConfig));
            }
            if (hash != null) body.put("hash", hash);
            if (updatedAt != null) body.put("updatedAt", updatedAt);
            return Response.ok(body).build();
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage() == null ? "invalid agent" : e.getMessage());
        }
    }

    @POST
    @Path("/agent")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response createAgent(Map<String, String> body) {
        Response denied = requireAdmin();
        if (denied != null) return denied;
        if (engine.isServerlessMode() || !engine.isAgentAuthoringEnabled()) {
            return authoringDisabled();
        }
        if (body == null || body.get("name") == null || body.get("name").isBlank()
                || body.get("content") == null) {
            return badRequest("name and content must not be blank");
        }
        try {
            engine.createAgent(body.get("name"), body.get("content"));
            return Response.ok(Map.of("ok", true, "name", body.get("name"))).build();
        } catch (IllegalArgumentException | java.io.UncheckedIOException e) {
            return badRequest(e.getMessage() == null ? "invalid agent" : e.getMessage());
        }
    }

    @PUT
    @Path("/agent/{name}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response updateAgent(@PathParam("name") String name, Map<String, String> body) {
        Response denied = requireAdmin();
        if (denied != null) return denied;
        if (engine.isServerlessMode() || !engine.isAgentAuthoringEnabled()) {
            return authoringDisabled();
        }
        if (name == null || name.isBlank()) {
            return badRequest("agent name must not be blank");
        }
        if (body == null || body.get("content") == null) {
            return badRequest("content must not be blank");
        }
        try {
            engine.createAgent(name, body.get("content"));
            return Response.ok(Map.of("ok", true, "name", name)).build();
        } catch (IllegalArgumentException | java.io.UncheckedIOException e) {
            return badRequest(e.getMessage() == null ? "invalid agent" : e.getMessage());
        }
    }

    @DELETE
    @Path("/agent/{name}")
    public Response deleteAgent(@PathParam("name") String name) {
        Response denied = requireAdmin();
        if (denied != null) return denied;
        if (engine.isServerlessMode() || !engine.isAgentAuthoringEnabled()) {
            return authoringDisabled();
        }
        if (name == null || name.isBlank()) {
            return badRequest("agent name must not be blank");
        }
        try {
            engine.deleteAgent(name);
            return Response.ok(Map.of("ok", true, "name", name)).build();
        } catch (IllegalArgumentException | java.io.UncheckedIOException e) {
            return badRequest(e.getMessage() == null ? "invalid agent" : e.getMessage());
        }
    }

    private boolean agentAllowed(String name) {
        return engine.agentAllowed(name, accessGuard.currentRoles());
    }

    private Response agentForbidden(String name) {
        return Response.status(Response.Status.FORBIDDEN)
                .entity(Map.of("_tag", "Forbidden", "message", "agent not allowed: " + name))
                .build();
    }

    private Response requireAdmin() {
        if (accessGuard.isAdmin()) return null;
        return Response.status(Response.Status.FORBIDDEN)
                .entity(Map.of("_tag", "Forbidden", "message", "requires role: admin"))
                .build();
    }

    private static Response authoringDisabled() {
        return Response.status(Response.Status.FORBIDDEN)
                .entity(Map.of("error", "agent authoring is disabled")).build();
    }

    private static Response badRequest(String message) {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(Map.of("_tag", "BadRequest", "message", message == null ? "bad request" : message))
                .build();
    }
}
