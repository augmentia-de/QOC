package de.augmentia.oc.engine.http;

import de.augmentia.oc.engine.core.model.SessionId;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.LinkedHashMap;
import java.util.Map;

/** File/workspace endpoints extracted from {@link HttpApi} (H7). */
@Path("/api")
@Produces(MediaType.APPLICATION_JSON)
public class WorkspaceFileResource {

    @Inject
    Engine engine;

    @Inject
    SessionAccessGuard accessGuard;

    private String currentUser() {
        return accessGuard.currentUser();
    }

    private boolean currentUserIsAdmin() {
        return accessGuard.isAdmin();
    }

    private Response requireRole(String role) {
        if (accessGuard.hasRole(role)) return null;
        return Response.status(Response.Status.FORBIDDEN)
                .entity(Map.of("_tag", "Forbidden", "message", "requires role: " + role))
                .build();
    }

    @GET
    @Path("/files")
    public Response files(@QueryParam("path") String path) {
        try {
            return Response.ok(Map.of("data", engine.listFiles(path, currentUser(), currentUserIsAdmin()))).build();
        } catch (RuntimeException e) {
            return badRequest(e.getMessage());
        }
    }

    @GET
    @Path("/file")
    public Response file(@QueryParam("path") String path) {
        try {
            String full = engine.readFile(path, currentUser(), currentUserIsAdmin());
            boolean truncated = full.length() > MAX_FILE_VIEW_CHARS;
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("path", path);
            data.put("content", truncated ? full.substring(0, MAX_FILE_VIEW_CHARS) : full);
            data.put("truncated", truncated);
            data.put("totalChars", full.length());
            return Response.ok(Map.of("data", data)).build();
        } catch (RuntimeException e) {
            return badRequest(e.getMessage());
        }
    }

    @GET
    @Path("/file/download")
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    public Response downloadFile(@QueryParam("path") String path) {
        try {
            byte[] content = engine.readFileBytes(path, currentUser(), currentUserIsAdmin());
            return Response.ok(content)
                    .header("Content-Disposition", attachment(path))
                    .build();
        } catch (RuntimeException e) {
            return badRequest(e.getMessage());
        }
    }

    @GET
    @Path("/dir/download")
    @Produces("application/zip")
    public Response downloadDirectory(@QueryParam("path") String path) {
        Response denied = requireRole("admin");
        if (denied != null) return denied;
        try {
            byte[] zip = engine.zipDirectory(path);
            return Response.ok(zip)
                    .header("Content-Disposition",
                            "attachment; filename=\"" + fileName(path) + ".zip\"")
                    .build();
        } catch (RuntimeException e) {
            return badRequest(e.getMessage());
        }
    }

    @POST
    @Path("/file")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response createFile(Map<String, String> body) {
        if (body == null || body.get("path") == null || body.get("path").isBlank()) {
            return badRequest("path must not be blank");
        }
        String path = body.get("path");
        String content = body.get("content") == null ? "" : body.get("content");
        if (content.length() > MAX_EDIT_CHARS) {
            return Response.status(Response.Status.REQUEST_ENTITY_TOO_LARGE)
                    .entity(Map.of("_tag", "TooLarge",
                            "message", "content exceeds " + MAX_EDIT_CHARS + " characters")).build();
        }
        String lower = path.toLowerCase();
        int dot = lower.lastIndexOf('.');
        String ext = dot >= 0 ? lower.substring(dot + 1) : "";
        if (!ext.isBlank() && !TEXT_EDIT_EXTENSIONS.contains(ext)) {
            return badRequest("file type not editable: " + ext);
        }
        try {
            engine.createFile(path, content, currentUser(), currentUserIsAdmin());
            return Response.ok(Map.of("ok", true)).build();
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
    }

    @POST
    @Path("/file/upload")
    @Consumes(MediaType.APPLICATION_OCTET_STREAM)
    public Response uploadFile(@QueryParam("path") String path, byte[] body) {
        Response denied = requireRole("admin");
        if (denied != null) return denied;
        if (path == null || path.isBlank()) {
            return badRequest("path must not be blank");
        }
        try {
            engine.writeFile(path, body == null ? new byte[0] : body);
            return Response.ok(Map.of("ok", true)).build();
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
    }

    @DELETE
    @Path("/file")
    public Response deleteFile(@QueryParam("path") String path) {
        try {
            engine.deletePath(path, currentUser(), currentUserIsAdmin());
            return Response.ok(Map.of("ok", true)).build();
        } catch (RuntimeException e) {
            return badRequest(e.getMessage());
        }
    }

    private static Response badRequest(String message) {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(Map.of("_tag", "BadRequest", "message", message == null ? "bad request" : message))
                .build();
    }

    private static String attachment(String path) {
        String name = path == null ? "file" : path.substring(path.lastIndexOf('/') + 1);
        if (name.isBlank()) name = "file";
        return "attachment; filename=\"" + name + "\"";
    }

    private static String fileName(String path) {
        if (path == null || path.isBlank()) return "workspace";
        String name = path.substring(path.lastIndexOf('/') + 1);
        return name.isBlank() ? "workspace" : name;
    }

    private static final int MAX_FILE_VIEW_CHARS = 1_000_000;
    private static final int MAX_EDIT_CHARS = 1_000_000;
    private static final java.util.Set<String> TEXT_EDIT_EXTENSIONS = java.util.Set.of(
            "txt", "md", "json", "yaml", "yml", "xml", "html", "css", "js", "ts",
            "java", "py", "sh", "bash", "properties", "conf", "cfg", "ini", "toml",
            "csv", "log", "sql", "kt", "go", "rs", "c", "cpp", "h", "hpp");
}
