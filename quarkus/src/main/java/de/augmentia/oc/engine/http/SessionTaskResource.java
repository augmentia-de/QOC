package de.augmentia.oc.engine.http;

import de.augmentia.oc.engine.core.model.SessionId;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Map;

/** Process/background-task endpoints extracted from {@link HttpApi} (H7). */
@Path("/api")
@Produces(MediaType.APPLICATION_JSON)
public class SessionTaskResource {

    @Inject
    Engine engine;

    @Inject
    SessionAccessGuard accessGuard;

    @GET
    @Path("/session/{id}/processes")
    public Response processes(@PathParam("id") String id) {
        Response denied = accessGuard.requireAccess(id);
        if (denied != null) return denied;
        try {
            List<Map<String, Object>> data = engine.processes(SessionId.parse(id)).stream()
                    .map(SessionDtos::process).toList();
            return Response.ok(Map.of("data", data)).build();
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
    }

    @POST
    @Path("/session/{id}/processes/{processId}/kill")
    public Response killProcess(@PathParam("id") String id, @PathParam("processId") String processId) {
        Response denied = accessGuard.requireAccess(id);
        if (denied != null) return denied;
        try {
            boolean killed = engine.killProcess(SessionId.parse(id), processId);
            return Response.ok(Map.of("killed", killed)).build();
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
    }

    @GET
    @Path("/session/{id}/tasks")
    public Response tasks(@PathParam("id") String id) {
        Response denied = accessGuard.requireAccess(id);
        if (denied != null) return denied;
        try {
            List<Map<String, Object>> data = engine.tasks(SessionId.parse(id)).stream()
                    .map(SessionDtos::task).toList();
            return Response.ok(Map.of("data", data)).build();
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
    }

    @POST
    @Path("/session/{id}/tasks/{taskId}/cancel")
    public Response cancelTask(@PathParam("id") String id, @PathParam("taskId") String taskId) {
        Response denied = accessGuard.requireAccess(id);
        if (denied != null) return denied;
        try {
            boolean cancelled = engine.cancelTask(SessionId.parse(id), taskId);
            return Response.ok(Map.of("cancelled", cancelled)).build();
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
    }

    private static Response badRequest(String message) {
        return Response.status(Response.Status.BAD_REQUEST)
                .type(MediaType.APPLICATION_JSON)
                .entity(Map.of("_tag", "InvalidRequestError",
                        "message", message == null ? "invalid request" : message))
                .build();
    }
}
