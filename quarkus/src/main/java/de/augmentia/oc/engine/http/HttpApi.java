package de.augmentia.oc.engine.http;

import de.augmentia.oc.engine.core.model.SessionId;
import de.augmentia.oc.engine.core.model.SessionInfo;
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
    EventBus eventBus;

    @POST
    @Path("/session")
    public Response createSession() {
        SessionId id = engine.createSession();
        return Response.ok(Map.of("id", id.value())).build();
    }

    @GET
    @Path("/session/{id}")
    public Response session(@PathParam("id") String id) {
        try {
            var info = engine.session(SessionId.parse(id));
            if (info.isEmpty()) return Response.status(Response.Status.NOT_FOUND).build();
            SessionInfo session = info.orElseThrow();
            return Response.ok(new SessionDto(session.id().value(), session.title(),
                    session.agent().value(), session.location().directory().toString(),
                    session.cost().toString(), session.tokens().total())).build();
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "invalid session id")).build();
        }
    }

    @POST
    @Path("/session/{id}/prompt")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response prompt(@PathParam("id") String id, PromptRequest request) {
        if (request == null || request.text() == null || request.text().isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "text must not be blank")).build();
        }
        try {
            Engine.PromptResult result = engine.prompt(SessionId.parse(id), request.text());
            return Response.ok(new PromptResponse(result.finish(), result.inputTokens(),
                    result.outputTokens(), result.text())).build();
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", e.getMessage())).build();
        } catch (IllegalStateException e) {
            return Response.status(422).entity(Map.of("error", e.getMessage())).build();
        }
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
                Runnable unsubscribe = eventBus.subscribe(id.value(),
                        event -> emitter.emit(engine.encode(event)));
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
        try {
            SessionId session = SessionId.parse(id);
            List<MessageDto> messages = engine.messages(session).stream()
                    .map(message -> new MessageDto(message.id().value(),
                            message.getClass().getSimpleName(), text(message)))
                    .toList();
            return Response.ok(messages).build();
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "invalid session id")).build();
        }
    }

    @POST
    @Path("/session/{id}/interrupt")
    public Response interrupt(@PathParam("id") String id) {
        try {
            return Response.ok(Map.of("interrupted", engine.interrupt(SessionId.parse(id)))).build();
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "invalid session id")).build();
        }
    }

    @GET
    @Path("/agent")
    public List<AgentCatalogue.AgentDto> agents() {
        return engine.agents();
    }

    @GET
    @Path("/config")
    public Engine.ConfigDto config() {
        return engine.config();
    }

    @GET
    @Path("/model")
    public Engine.ModelDto model() {
        return engine.model();
    }

    public record PromptRequest(String text) {}

    public record PromptResponse(String finish, long inputTokens, long outputTokens, String text) {}

    public record SessionDto(String id, String title, String agent, String directory,
                             String cost, long tokens) {}

    public record MessageDto(String id, String type, String text) {}

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
