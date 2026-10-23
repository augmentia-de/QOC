package de.augmentia.oc.engine.http;

import de.augmentia.oc.engine.core.model.SessionId;
import de.augmentia.oc.engine.core.model.SessionInfo;
import io.smallrye.mutiny.Multi;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** HTTP/SSE surface of the engine (M14). */
@Path("/api")
@Produces(MediaType.APPLICATION_JSON)
public class HttpApi {

    @Inject
    Engine engine;

    @Inject
    EventBus eventBus;

    @POST
    @Path("/session")
    public Response createSession() {
        SessionId id = engine.createSession();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", id.value());
        engine.session(id).ifPresent(info -> body.put("data", dto(info)));
        return Response.ok(body).build();
    }

    @GET
    @Path("/session")
    public Response listSessions() {
        return Response.ok(Map.of("data", engine.listSessions().stream().map(HttpApi::dto).toList()))
                .build();
    }

    @GET
    @Path("/session/{id}")
    public Response session(@PathParam("id") String id) {
        try {
            var info = engine.session(SessionId.parse(id));
            if (info.isEmpty()) return Response.status(Response.Status.NOT_FOUND).build();
            return Response.ok(dto(info.orElseThrow())).build();
        } catch (IllegalArgumentException e) {
            return badRequest("invalid session id");
        }
    }

    @POST
    @Path("/session/{id}/prompt")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response prompt(@PathParam("id") String id, PromptRequest request) {
        if (request == null || request.text() == null || request.text().isBlank()) {
            return badRequest("text must not be blank");
        }
        return runPrompt(id, request.text());
    }

    @POST
    @Path("/session/{id}/command")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response command(@PathParam("id") String id, CommandRequest request) {
        if (request == null || request.name() == null || request.name().isBlank()) {
            return badRequest("name must not be blank");
        }
        String arguments = request.arguments() == null ? "" : request.arguments();
        return runPrompt(id, "/" + request.name() + (arguments.isBlank() ? "" : " " + arguments));
    }

    @GET
    @Path("/event")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    public Multi<String> events(@QueryParam("session") String session,
                                @QueryParam("after") @DefaultValue("0") long after,
                                @QueryParam("live") @DefaultValue("true") boolean live) {
        try {
            SessionId id = SessionId.parse(session);
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
        try {
            SessionId session = SessionId.parse(id);
            List<MessageDto> messages = engine.messages(session).stream()
                    .map(HttpApi::message).toList();
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

    @POST
    @Path("/session/{id}/title")
    public Response title(@PathParam("id") String id) {
        try {
            String title = engine.generateTitle(SessionId.parse(id));
            return Response.ok(Map.of("title", title == null ? "" : title)).build();
        } catch (IllegalArgumentException e) {
            return badRequest("invalid session id");
        }
    }

    @POST
    @Path("/session/{id}/interrupt")
    public Response interrupt(@PathParam("id") String id) {
        try {
            return Response.ok(Map.of("interrupted", engine.interrupt(SessionId.parse(id)))).build();
        } catch (IllegalArgumentException e) {
            return badRequest("invalid session id");
        }
    }

    @GET
    @Path("/session/{id}/permission")
    public Response sessionPermissions(@PathParam("id") String id) {
        return Response.ok(Map.of("data", List.of())).build();
    }

    @POST
    @Path("/session/{id}/permission/{requestId}/reply")
    public Response replyPermission(@PathParam("id") String id, @PathParam("requestId") String requestId) {
        return Response.status(Response.Status.NOT_FOUND)
                .entity(Map.of("_tag", "PermissionNotFoundError", "requestID", requestId,
                        "message", "no open permission request")).build();
    }

    @GET
    @Path("/permission/request")
    public Response openPermissions() {
        return Response.ok(Map.of("location", engine.location(), "data", List.of())).build();
    }

    @GET
    @Path("/files")
    public Response files(@QueryParam("path") String path) {
        try {
            return Response.ok(Map.of("data", engine.listFiles(path))).build();
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
    }

    @GET
    @Path("/file")
    public Response file(@QueryParam("path") String path) {
        try {
            String content = engine.readFile(path);
            return Response.ok(Map.of("data", Map.of("path", path, "content", content))).build();
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
    }

    @GET
    @Path("/mcp")
    public Response mcpServers() {
        return Response.ok(Map.of("data", engine.mcpServerNames())).build();
    }

    @GET
    @Path("/mcp/{server}/status")
    public Response mcpStatus(@PathParam("server") String server) {
        return Response.ok(Map.of("status", engine.mcpStatus(server), "error", engine.mcpError(server))).build();
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

    @GET
    @Path("/agent")
    public Response agents() {
        return Response.ok(Map.of("location", engine.location(), "data", engine.agents())).build();
    }

    @GET
    @Path("/skill")
    public Response skills() {
        return Response.ok(Map.of("location", engine.location(), "data", List.of())).build();
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

    @GET
    @Path("/config")
    public List<EngineConfig.ConfigEntry> config() {
        return engine.configEntries();
    }

    @GET
    @Path("/health")
    public Response health() {
        return Response.ok(engine.health()).build();
    }

    @POST
    @Path("/file")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response createFile(Map<String, String> body) {
        if (body == null || body.get("path") == null || body.get("path").isBlank()) {
            return badRequest("path must not be blank");
        }
        try {
            engine.createFile(body.get("path"), body.get("content"));
            return Response.ok(Map.of("ok", true)).build();
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
    }

    @DELETE
    @Path("/file")
    public Response deleteFile(@QueryParam("path") String path) {
        try {
            engine.deleteFile(path);
            return Response.ok(Map.of("ok", true)).build();
        } catch (RuntimeException e) {
            return badRequest(e.getMessage());
        }
    }

    @GET
    @Path("/location")
    public Engine.LocationDto location() {
        return engine.location();
    }

    private Response runPrompt(String id, String text) {
        try {
            Engine.PromptResult result = engine.prompt(SessionId.parse(id), text);
            return Response.ok(new PromptResponse(result.finish(), result.inputTokens(),
                    result.outputTokens(), result.text())).build();
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        } catch (IllegalStateException e) {
            return Response.status(422).entity(Map.of("_tag", "UnknownError", "message", e.getMessage())).build();
        }
    }

    private static Response badRequest(String message) {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(Map.of("_tag", "InvalidRequestError", "message", message)).build();
    }

    private static SessionDto dto(SessionInfo session) {
        return new SessionDto(session.id().value(), session.title(), session.agent().value(),
                session.location().directory().toString(), session.cost().toString(),
                session.tokens().total());
    }

    private static MessageDto message(de.augmentia.oc.engine.core.model.Message message) {
        return new MessageDto(message.id().value(), message.getClass().getSimpleName(), text(message));
    }

    public record PromptRequest(String text) {}

    public record CreateSessionRequest(String agent) {}

    public record CommandRequest(String name, String arguments) {}

    public record PromptResponse(String finish, long inputTokens, long outputTokens, String text) {}

    public record SessionDto(String id, String title, String agent, String directory,
                             String cost, long tokens) {}

    public record MessageDto(String id, String type, String text) {}

    public record ProviderDto(String id, String name, boolean available) {}

    public record IntegrationDto(String id, String name, boolean connected) {}

    private static String text(de.augmentia.oc.engine.core.model.Message message) {
        return switch (message) {
            case de.augmentia.oc.engine.core.model.Message.UserMessage user -> user.text();
            case de.augmentia.oc.engine.core.model.Message.SyntheticMessage synthetic -> synthetic.text();
            case de.augmentia.oc.engine.core.model.Message.SystemMessage system -> system.text();
            case de.augmentia.oc.engine.core.model.Message.SkillMessage skill -> skill.text();
            case de.augmentia.oc.engine.core.model.Message.AssistantMessage assistant ->
                    assistant.content().stream()
                            .filter(item -> item instanceof de.augmentia.oc.engine.core.model.Content.TextContent)
                            .map(item -> ((de.augmentia.oc.engine.core.model.Content.TextContent) item).text())
                            .reduce("", String::concat);
            default -> "";
        };
    }
}
