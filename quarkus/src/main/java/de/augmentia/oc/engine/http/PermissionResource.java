package de.augmentia.oc.engine.http;

import de.augmentia.oc.engine.core.contract.permission.PermissionRequest;
import de.augmentia.oc.engine.core.contract.permission.Reply;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.PermissionRequestId;
import de.augmentia.oc.engine.core.model.SessionId;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Map;

/** Permission endpoints extracted from {@link HttpApi} (H7). */
@Path("/api")
@Produces(MediaType.APPLICATION_JSON)
public class PermissionResource {

    @Inject
    Engine engine;

    @Inject
    SessionAccessGuard accessGuard;

    @GET
    @Path("/session/{id}/permission")
    public Response sessionPermissions(@PathParam("id") String id,
                                       @QueryParam("includeChildren") @DefaultValue("false") boolean includeChildren) {
        Response denied = accessGuard.requireAccess(id);
        if (denied != null) return denied;
        try {
            List<PermissionRequest> pending = includeChildren
                    ? engine.pendingPermissionsForChildren(SessionId.parse(id))
                    : engine.pendingPermissions(SessionId.parse(id));
            List<Map<String, Object>> data = pending.stream().map(PermissionDtos::toMap).toList();
            return Response.ok(Map.of("data", data)).build();
        } catch (IllegalArgumentException e) {
            return badRequest("invalid session id");
        }
    }

    @POST
    @Path("/session/{id}/permission/{requestId}/reply")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response replyPermission(@PathParam("id") String id, @PathParam("requestId") String requestId,
                                    Map<String, Object> body) {
        Response denied = accessGuard.requireAccess(id);
        if (denied != null) return denied;
        try {
            SessionId session = SessionId.parse(id);
            PermissionRequestId reqId = PermissionRequestId.parse(requestId);
            String replyStr = body == null ? null : (String) body.get("reply");
            Reply reply = replyStr == null ? Reply.ONCE : Reply.valueOf(replyStr.toUpperCase());
            String feedback = body == null ? null : (String) body.get("feedback");
            Option<String> feedbackOpt = feedback == null || feedback.isBlank()
                    ? Option.none() : Option.some(feedback);
            var outcome = engine.replyPermission(session, reqId, reply, feedbackOpt);
            engine.resolvePermission(session, reqId, reply, feedbackOpt);
            if (outcome.isEmpty()) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(Map.of("_tag", "PermissionNotFoundError", "requestID", requestId,
                                "message", "no open permission request")).build();
            }
            return Response.noContent().build();
        } catch (IllegalArgumentException e) {
            return badRequest("invalid id");
        }
    }

    private static Response badRequest(String message) {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(Map.of("_tag", "BadRequest", "message", message == null ? "bad request" : message))
                .build();
    }
}
