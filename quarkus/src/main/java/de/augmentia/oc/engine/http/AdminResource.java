package de.augmentia.oc.engine.http;

import de.augmentia.oc.engine.core.model.SessionId;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Map;

/** Admin/system endpoints extracted from {@link HttpApi} (H7). */
@Path("/api")
@Produces(MediaType.APPLICATION_JSON)
public class AdminResource {

    private static final java.util.logging.Logger LOG = java.util.logging.Logger.getLogger(AdminResource.class.getName());

    @Inject
    Engine engine;

    @Inject
    de.augmentia.oc.engine.http.security.PrincipalContext principalContext;

    @Inject
    de.augmentia.oc.engine.http.EngineMetrics metrics;

    @Inject
    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "engine.llm-log.path", defaultValue = "./log/llm.log")
    String llmLogPath;

    private List<String> currentRoles() {
        if (principalContext == null) return List.of();
        var principal = principalContext.get();
        if (principal == null || principal.roles() == null) return List.of();
        return principal.roles();
    }

    private boolean hasRole(String role) {
        if (role == null || role.isBlank()) return true;
        return AgentAccess.hasRole(currentRoles(), role);
    }

    private Response requireRole(String role) {
        if (hasRole(role)) return null;
        return Response.status(Response.Status.FORBIDDEN)
                .entity(Map.of("_tag", "Forbidden", "message", "requires role: " + role))
                .build();
    }

    private static Response badRequest(String message) {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(Map.of("_tag", "BadRequest", "message", message == null ? "bad request" : message))
                .build();
    }

    @GET
    @Path("/health/live")
    public Response healthLive() {
        return Response.ok(Map.of("status", "UP", "check", "live")).build();
    }

    @GET
    @Path("/health/ready")
    public Response healthReady() {
        boolean ready = engine.ready();
        return Response.status(ready ? Response.Status.OK : Response.Status.SERVICE_UNAVAILABLE)
                .entity(Map.of("status", ready ? "UP" : "DOWN", "check", "ready"))
                .build();
    }

    @GET
    @Path("/health")
    public Response health() {
        return Response.ok(engine.health()).build();
    }

    @GET
    @Path("/metrics")
    public Response metrics() {
        Response denied = requireRole("admin");
        if (denied != null) return denied;
        return Response.ok(metrics.snapshot()).build();
    }

    @GET
    @Path("/audit")
    public Response audit(@QueryParam("session") String session,
                          @QueryParam("limit") @DefaultValue("100") int limit) {
        Response denied = requireRole("admin");
        if (denied != null) return denied;
        if (session == null || session.isBlank()) {
            return badRequest("session must not be blank");
        }
        try {
            return Response.ok(Map.of("data", engine.audit(SessionId.parse(session), limit))).build();
        } catch (IllegalArgumentException e) {
            return badRequest("invalid session id");
        }
    }

    @GET
    @Path("/config")
    public List<EngineConfig.ConfigEntry> config() {
        return engine.configEntries();
    }

    @POST
    @Path("/config/reload")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response reloadConfig(@HeaderParam("X-QOC-User") String user) {
        Response denied = requireRole("admin");
        if (denied != null) return denied;
        String normalized = (user == null || user.isBlank()) ? "opencode" : user.trim();
        if (!"opencode".equalsIgnoreCase(normalized)) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("_tag", "Forbidden", "message", "config reload requires the opencode user"))
                    .build();
        }
        engine.reloadConfig();
        return Response.ok(Map.of("status", "reloaded")).build();
    }

    @GET
    @Path("/location")
    public Engine.LocationDto location() {
        return engine.location();
    }

    @GET
    @Path("/llm/trace")
    public Response llmTrace(@QueryParam("offset") @DefaultValue("0") int offset,
                             @QueryParam("limit") @DefaultValue("8000") int limit) {
        try {
            java.util.List<java.nio.file.Path> candidateDirs = new java.util.ArrayList<>();
            try { candidateDirs.add(java.nio.file.Path.of(llmLogPath).toAbsolutePath().normalize().getParent()); } catch (Exception e) { LOG.fine("ignored: " + e.getMessage()); }
            for (String d : new String[]{"/app/log", "/app/quarkus/log", "/app/data/home/log", "/data/log", "."}) {
                candidateDirs.add(java.nio.file.Path.of(d));
            }
            String prefix = "llm.log";
            java.util.List<java.nio.file.Path> files = new java.util.ArrayList<>();
            for (java.nio.file.Path dir : candidateDirs) {
                if (dir == null || !java.nio.file.Files.isDirectory(dir)) continue;
                try (var stream = java.nio.file.Files.list(dir)) {
                    for (java.nio.file.Path pth : stream.toList()) {
                        if (!java.nio.file.Files.isRegularFile(pth)) continue;
                        if (!pth.getFileName().toString().startsWith(prefix)) continue;
                        files.add(pth);
                    }
                } catch (Exception e) { LOG.fine("ignored: " + e.getMessage()); }
            }
            files.sort(java.util.Comparator.comparingLong(pth -> {
                try { return java.nio.file.Files.getLastModifiedTime(pth).toMillis(); }
                catch (Exception e) { return 0L; }
            }));
            StringBuilder all = new StringBuilder();
            for (java.nio.file.Path f : files) {
                try {
                    all.append(java.nio.file.Files.readString(f, java.nio.charset.StandardCharsets.UTF_8));
                    all.append('\n');
                } catch (Exception e) { LOG.fine("ignored: " + e.getMessage()); }
            }
            String full = all.toString();
            int total = full.length();
            int off = Math.max(0, Math.min(offset, total));
            int lim = limit <= 0 ? 8000 : limit;
            int end = Math.min(total, off + lim);
            String slice = full.substring(off, end);
            boolean hasMore = end < total;
            return Response.ok(Map.of("data", Map.of("lines", slice, "offset", off,
                    "limit", lim, "hasMore", hasMore, "total", total))).build();
        } catch (Exception e) {
            return badRequest(e.getMessage() == null ? "cannot read llm trace" : e.getMessage());
        }
    }

    @GET
    @Path("/permission/request")
    public Response openPermissions() {
        List<Map<String, Object>> data = engine.allPendingPermissions().stream().map(PermissionDtos::toMap).toList();
        return Response.ok(Map.of("location", engine.location(), "data", data)).build();
    }
}
