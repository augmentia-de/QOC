package de.augmentia.oc.engine.http;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Map;

/** Tool/model/provider/workspace catalogue endpoints extracted from {@link HttpApi} (H7). */
@Path("/api")
@Produces(MediaType.APPLICATION_JSON)
public class CatalogResource {

    @Inject
    Engine engine;

    @Inject
    SessionAccessGuard accessGuard;

    @GET
    @Path("/tool")
    public Response tools() {
        return Response.ok(Map.of("location", engine.location(), "data", engine.availableTools())).build();
    }

    @GET
    @Path("/a2a")
    public Response a2aTools() {
        return Response.ok(Map.of("location", engine.location(), "data", engine.a2aToolNames())).build();
    }

    @GET
    @Path("/skill")
    public Response skills(@QueryParam("all") @DefaultValue("false") boolean all) {
        return Response.ok(Map.of("location", engine.location(), "data", engine.skills(all))).build();
    }

    @GET
    @Path("/model")
    public Response models() {
        return Response.ok(Map.of("location", engine.location(), "data", List.of(engine.model()))).build();
    }

    @GET
    @Path("/provider")
    public Response providers() {
        Engine.ConfigDto config = engine.config();
        return Response.ok(Map.of("location", engine.location(), "data",
                List.of(new ProviderDto(config.provider(), config.provider(), true)))).build();
    }

    @GET
    @Path("/integration")
    public Response integrations() {
        Engine.ConfigDto config = engine.config();
        return Response.ok(Map.of("location", engine.location(), "data",
                List.of(new IntegrationDto(config.provider(), config.provider(), true)))).build();
    }

    @GET
    @Path("/credential")
    public Response credentials() {
        return Response.ok(Map.of("data", List.of())).build();
    }

    @POST
    @Path("/workspace")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response reloadWorkspace(Map<String, Object> body, @HeaderParam("X-QOC-User") String user) {
        String normalizedUser = user;
        if (normalizedUser == null || normalizedUser.isBlank()) {
            normalizedUser = accessGuard.currentUser();
        }
        if (normalizedUser == null || normalizedUser.isBlank()) {
            normalizedUser = "anonymous";
        }
        boolean isAdmin = accessGuard.isAdmin();
        if (body == null || body.get("path") == null || String.valueOf(body.get("path")).isBlank()) {
            return badRequest("path must not be blank");
        }
        boolean create = Boolean.TRUE.equals(body.get("create"));
        String path = String.valueOf(body.get("path")).trim();
        try {
            java.nio.file.Path p = java.nio.file.Path.of(path);
            if (create && !java.nio.file.Files.exists(p)) {
                java.nio.file.Files.createDirectories(p);
            }
            engine.reloadWorkspace(path, normalizedUser, isAdmin);
            return Response.ok(Map.of("status", "reloaded", "workspace", engine.config().workspace())).build();
        } catch (IllegalArgumentException | java.io.IOException e) {
            return badRequest(e.getMessage() == null ? "invalid workspace" : e.getMessage());
        }
    }

    @GET
    @Path("/workspace")
    public Response workspace(@HeaderParam("X-QOC-User") String user) {
        String ws = engine.config().workspace();
        String userWs = ws;
        String effectiveUser = user;
        if (effectiveUser == null || effectiveUser.isBlank()) {
            effectiveUser = accessGuard.currentUser();
        }
        if (effectiveUser != null && !effectiveUser.isBlank() && !"opencode".equalsIgnoreCase(effectiveUser)
                && !"anonymous".equalsIgnoreCase(effectiveUser) && !"local".equalsIgnoreCase(effectiveUser)) {
            java.nio.file.Path base = engine.workspaceBase() != null ? engine.workspaceBase() : java.nio.file.Path.of(ws);
            java.nio.file.Path uw = base.resolve("user-ws").resolve(effectiveUser).normalize();
            if (uw.startsWith(base) && java.nio.file.Files.isDirectory(uw)) {
                userWs = uw.toString();
            }
        }
        return Response.ok(Map.of("location", engine.location(), "workspace", ws, "userWorkspace", userWs)).build();
    }

    private static Response badRequest(String message) {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(Map.of("_tag", "BadRequest", "message", message == null ? "bad request" : message))
                .build();
    }
}
