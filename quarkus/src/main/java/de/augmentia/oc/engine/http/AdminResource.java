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
    @Path("/admin/ping")
    public Response adminPing() {
        Response denied = requireRole("admin");
        if (denied != null) return denied;
        var p = principalContext == null ? null : principalContext.get();
        String id = p == null ? "anonymous" : p.id();
        return Response.ok(Map.of("status", "ok", "user", id, "message", "admin-only endpoint")).build();
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
    public Response llmTrace(@QueryParam("offset") @DefaultValue("-1") int offset,
                             @QueryParam("limit") @DefaultValue("8000") int limit,
                             @QueryParam("tail") @DefaultValue("-1") int tail) {
        // LLM trace is read-only diagnostic data; any authenticated user may
        // view it (admin-only gating hid traces behind a 403 in OIDC mode).
        try {
            java.nio.file.Path configured = java.nio.file.Path.of(llmLogPath).toAbsolutePath().normalize();
            java.util.List<java.nio.file.Path> files = traceFiles(configured.getParent());
            if (files.isEmpty()) {
                // Only fall back when the configured directory has no trace at
                // all. Never merge logs from several directories: stale copies
                // from other deployments would otherwise surface as current.
                for (String d : new String[]{"/app/log", "/data/log", "."}) {
                    files = traceFiles(java.nio.file.Path.of(d));
                    if (!files.isEmpty()) break;
                }
            }
            // Newest file last. FileHandler names the active generation
            // llm.log.0; mtime keeps rotated generations in append order.
            files.sort(java.util.Comparator.comparingLong(AdminResource::lastModified));

            int lim = limit <= 0 ? 8000 : limit;
            int wanted = tail > 0 ? Math.max(tail, lim) : Math.max(lim, 1);
            String full = readTail(files, wanted);
            int total = full.length();
            long totalBytes = 0;
            for (java.nio.file.Path f : files) {
                try { totalBytes += java.nio.file.Files.size(f); }
                catch (Exception e) { LOG.fine("ignored: " + e.getMessage()); }
            }
            int off = offset >= 0
                    ? Math.max(0, Math.min(offset, total))
                    : Math.max(0, total - lim);
            int end = Math.min(total, off + lim);
            String slice = full.substring(off, end);
            boolean hasMore = off > 0 || totalBytes > total;
            // Flat shape expected by the web UI: data = lines text, total = loaded length.
            return Response.ok(Map.of("data", slice, "offset", off,
                    "limit", lim, "hasMore", hasMore, "total", total)).build();
        } catch (Exception e) {
            return badRequest(e.getMessage() == null ? "cannot read llm trace" : e.getMessage());
        }
    }

    private static long lastModified(java.nio.file.Path pth) {
        try {
            return java.nio.file.Files.getLastModifiedTime(pth).toMillis();
        } catch (Exception e) {
            return 0L;
        }
    }

    /** Rotation files of one trace, without lock files and without foreign logs. */
    private static java.util.List<java.nio.file.Path> traceFiles(java.nio.file.Path dir) {
        java.util.List<java.nio.file.Path> files = new java.util.ArrayList<>();
        if (dir == null || !java.nio.file.Files.isDirectory(dir)) return files;
        try (var stream = java.nio.file.Files.list(dir)) {
            for (java.nio.file.Path pth : stream.toList()) {
                if (!java.nio.file.Files.isRegularFile(pth)) continue;
                String name = pth.getFileName().toString();
                if (!name.startsWith("llm.log")) continue;
                if (name.endsWith(".lck")) continue;
                files.add(pth);
            }
        } catch (Exception e) {
            LOG.fine("ignored: " + e.getMessage());
        }
        return files;
    }

    /**
     * Reads at most {@code maxChars} characters from the end of the (already
     * sorted) trace files, newest file first. Avoids loading the whole 40 MiB
     * rotation just to show the newest entries.
     */
    private static String readTail(java.util.List<java.nio.file.Path> files, int maxChars) throws java.io.IOException {
        StringBuilder sb = new StringBuilder();
        for (int i = files.size() - 1; i >= 0 && sb.length() < maxChars; i--) {
            java.nio.file.Path f = files.get(i);
            byte[] bytes;
            try {
                long size = java.nio.file.Files.size(f);
                long keep = Math.min(size, maxChars - sb.length() + 1L);
                try (var in = java.nio.file.Files.newInputStream(f)) {
                    in.skipNBytes(size - keep);
                    bytes = in.readNBytes((int) keep);
                }
            } catch (Exception e) {
                LOG.fine("ignored: " + e.getMessage());
                continue;
            }
            String chunk = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            sb.insert(0, chunk + "\n");
        }
        String all = sb.toString();
        if (all.length() > maxChars) all = all.substring(all.length() - maxChars);
        return all;
    }

    @GET
    @Path("/permission/request")
    public Response openPermissions() {
        List<Map<String, Object>> data = engine.allPendingPermissions().stream().map(PermissionDtos::toMap).toList();
        return Response.ok(Map.of("location", engine.location(), "data", data)).build();
    }
}
