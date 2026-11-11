package de.augmentia.oc.engine.http;

import de.augmentia.oc.engine.core.model.SessionId;
import io.smallrye.mutiny.Multi;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Map;

/** HTTP/SSE surface of the engine (M14). */
@Path("/api")
@Produces(MediaType.APPLICATION_JSON)
public class HttpApi {

    @Inject
    Engine engine;

    @Inject
    DurableApprovalSource durableApprovalSource;

    @Inject
    EventBus eventBus;

    @Inject
    SessionAccessGuard accessGuard;

    @GET
    @Path("/event")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    public Multi<String> events(@QueryParam("session") String session,
                                @QueryParam("after") @DefaultValue("0") long after,
                                @QueryParam("live") @DefaultValue("true") boolean live) {
        try {
            SessionId id = SessionId.parse(session);
            Response denied = accessGuard.requireAccess(session);
            if (denied != null) {
                return Multi.createFrom().failure(new ForbiddenException(denied));
            }
            List<String> replay = engine.events(id, after).stream().map(engine::encode).toList();
            if (!live) return Multi.createFrom().items(replay.stream());
            return Multi.createFrom().emitter(emitter -> {
                // Subscribe before replay so no event can fall into the gap.
                Runnable unsubscribe = eventBus.subscribeLive(id.value(),
                        json -> emitter.emit(json));
                replay.forEach(emitter::emit);
                emitter.onTermination(unsubscribe::run);
            });
        } catch (IllegalArgumentException e) {
            return Multi.createFrom().failure(e);
        }
    }

    @GET
    @Path("/events")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    public Multi<String> globalEvents() {
        return Multi.createFrom().emitter(emitter -> {
            Runnable unsubscribe = eventBus.subscribeLive("questions",
                    json -> emitter.emit(json));
            emitter.onTermination(unsubscribe::run);
        });
    }

    @GET
    @Path("/approval/{token}")
    public Response getApproval(@PathParam("token") String token) {
        if (token == null || token.isBlank()) return badRequest("token required");
        String requestId = durableApprovalSource == null ? null : durableApprovalSource.findRequestIdByToken(
                DurableApprovalSource.sha256(token));
        if (requestId == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("_tag", "NotFound", "message", "approval not found or expired")).build();
        }
        return Response.ok(Map.of("requestId", requestId, "token", token)).build();
    }

    @POST
    @Path("/approval/{token}/reply")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response replyApproval(@PathParam("token") String token, ApprovalReplyRequest request) {
        if (token == null || token.isBlank()) return badRequest("token required");
        String decision = request == null || request.decision() == null ? "deny" : request.decision();
        String feedback = request == null ? null : request.feedback();
        boolean ok = durableApprovalSource != null
                && durableApprovalSource.resolve(token, decision, feedback);
        if (!ok) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("_tag", "NotFound", "message", "approval not found or already decided")).build();
        }
        // If approved, wake the paused session (best-effort, async).
        if ("approve".equalsIgnoreCase(decision) && durableApprovalSource != null) {
            String sessionId = durableApprovalSource.sessionIdForToken(token);
            if (sessionId != null && !sessionId.isBlank()) {
                try {
                    SessionId sid = SessionId.parse(sessionId);
                    new Thread(() -> {
                        try { engine.resume(sid); } catch (RuntimeException e) {
                            // logged
                        }
                    }, "resume-" + sessionId).start();
                } catch (IllegalArgumentException ignored) {}
            }
        }
        return Response.ok(Map.of("status", "decided", "decision", decision)).build();
    }

    private static Response badRequest(String message) {
        return Response.status(Response.Status.BAD_REQUEST)
                .type(MediaType.APPLICATION_JSON)
                .entity(Map.of("_tag", "InvalidRequestError",
                        "message", message == null ? "invalid request" : message))
                .build();
    }

    public record ApprovalReplyRequest(String decision, String feedback) {}


}
