package de.augmentia.oc.engine.http;

import de.augmentia.oc.engine.core.model.SessionId;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Message history endpoints extracted from {@link HttpApi} (H7). */
@Path("/api")
@Produces(MediaType.APPLICATION_JSON)
public class SessionMessageResource {

    @Inject
    Engine engine;

    @Inject
    SessionAccessGuard accessGuard;

    @GET
    @Path("/session/{id}/messages")
    public Response messages(@PathParam("id") String id) {
        return messagesPage(id, null, null);
    }

    @GET
    @Path("/session/{id}/message")
    public Response messagePage(@PathParam("id") String id,
                                @QueryParam("limit") @DefaultValue("200") int limit,
                                @QueryParam("cursor") String cursor) {
        return messagesPage(id, limit, cursor);
    }

    private Response messagesPage(String id, Integer limit, String cursor) {
        Response denied = accessGuard.requireAccess(id);
        if (denied != null) return denied;
        try {
            SessionId session = SessionId.parse(id);
            List<MessageDto> messages = engine.messages(session).stream()
                    .map(SessionDtos::message).toList();
            if (limit != null && limit > 0 && messages.size() > limit) {
                messages = messages.subList(messages.size() - limit, messages.size());
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("data", messages);
            body.put("cursor", null);
            return Response.ok(body).build();
        } catch (IllegalArgumentException e) {
            return badRequest("invalid session id");
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
