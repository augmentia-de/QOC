package de.augmentia.oc.engine.http;

import de.augmentia.oc.engine.core.json.JsonSchema;
import de.augmentia.oc.engine.core.json.JsonValues;
import de.augmentia.oc.engine.core.json.Result;
import de.augmentia.oc.engine.core.json.Schema;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.SessionId;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Session lifecycle/turn endpoints extracted from {@link HttpApi} (H7). */
@Path("/api")
@Produces(MediaType.APPLICATION_JSON)
public class SessionResource {

    @Inject
    Engine engine;

    @Inject
    SessionAccessGuard accessGuard;

    @POST
    @Path("/session")
    public Response createSession(CreateSessionRequest request,
                                 @HeaderParam("X-QOC-User") String user) {
        Response blocked = ExecutionGuards.registryBlocked(engine);
        if (blocked != null) return blocked;
        String principalUser = user;
        if (principalUser == null || principalUser.isBlank()) {
            principalUser = accessGuard.currentUser();
        }
        String mode = request == null ? null : request.mode();
        boolean retain = request != null && Boolean.TRUE.equals(request.retain());
        String agent = request == null ? null : request.agent();
        if (agent != null && !agent.isBlank()) {
            Response agentDenied = accessGuard.requireAgent(agent);
            if (agentDenied != null) return agentDenied;
        }
        SessionId id = engine.createSession(agent, principalUser,
                mode == null ? "workspace" : mode, retain, accessGuard.currentRoles());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", id.value());
        engine.session(id).ifPresent(info -> body.put("data", SessionDtos.session(info)));
        return Response.ok(body).build();
    }

    @POST
    @Path("/session/{id}/agent")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response switchAgent(@PathParam("id") String id, SwitchAgentRequest request) {
        Response denied = accessGuard.requireAccess(id);
        if (denied != null) return denied;
        if (request == null || request.agent() == null || request.agent().isBlank()) {
            return badRequest("agent must not be blank");
        }
        Response agentDenied = accessGuard.requireAgent(request.agent());
        if (agentDenied != null) return agentDenied;
        try {
            var selected = engine.switchAgent(SessionId.parse(id), request.agent());
            if (selected.isEmpty()) {
                return Response.status(Response.Status.NOT_FOUND).build();
            }
            var agent = selected.orElseThrow();
            return Response.ok(Map.of("agent", agent.value(), "name", engine.agentName(agent))).build();
        } catch (IllegalStateException e) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(Map.of("_tag", "SessionRunningError",
                            "message", e.getMessage() == null ? "session is running" : e.getMessage()))
                    .build();
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage() == null ? "invalid agent" : e.getMessage());
        }
    }

    @DELETE
    @Path("/session/{id}")
    public Response deleteSession(@PathParam("id") String id) {
        try {
            var info = engine.session(SessionId.parse(id));
            if (info.isPresent() && !accessGuard.isOwner(info.orElseThrow(), accessGuard.currentUser())) {
                return Response.status(Response.Status.FORBIDDEN).build();
            }
            boolean deleted = engine.deleteSession(SessionId.parse(id));
            if (!deleted) return Response.status(Response.Status.NOT_FOUND).build();
            return Response.noContent().build();
        } catch (IllegalStateException e) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(Map.of("_tag", "SessionRunningError",
                            "message", e.getMessage() == null ? "session is running" : e.getMessage()))
                    .build();
        } catch (IllegalArgumentException e) {
            return badRequest("invalid session id");
        }
    }

    @GET
    @Path("/session")
    public Response listSessions() {
        String current = accessGuard.currentUser();
        List<?> data = engine.listSessions(current, accessGuard.isAdmin()).stream()
                .map(SessionDtos::session)
                .toList();
        return Response.ok(Map.of("data", data)).build();
    }

    @GET
    @Path("/session/{id}")
    public Response session(@PathParam("id") String id) {
        try {
            var info = engine.session(SessionId.parse(id));
            if (info.isEmpty()) return Response.status(Response.Status.NOT_FOUND).build();
            if (!accessGuard.isOwner(info.orElseThrow(), accessGuard.currentUser())) {
                return Response.status(Response.Status.FORBIDDEN).build();
            }
            return Response.ok(SessionDtos.session(info.orElseThrow())).build();
        } catch (IllegalArgumentException e) {
            return badRequest("invalid session id");
        }
    }

    @POST
    @Path("/run")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response run(RunRequest request,
                        @HeaderParam("X-QOC-User") String user) {
        Response blocked = ExecutionGuards.registryBlocked(engine);
        if (blocked != null) return blocked;
        if (request == null || request.prompt() == null || request.prompt().isBlank()) {
            return badRequest("prompt must not be blank");
        }
        String mode = request.mode() == null || request.mode().isBlank() ? "workspace" : request.mode();
        boolean retain = Boolean.TRUE.equals(request.retain());
        if (request.agent() != null && !request.agent().isBlank()) {
            Response agentDenied = accessGuard.requireAgent(request.agent());
            if (agentDenied != null) return agentDenied;
        }
        SessionId id = engine.createSession(request.agent(), user == null ? "anonymous" : user, mode, retain,
                accessGuard.currentRoles());
        try {
            Engine.PromptResult result = engine.prompt(id, request.prompt(), Option.none());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("sessionId", id.value());
            body.put("text", result.text());
            body.put("finish", result.finish());
            body.put("inputTokens", result.inputTokens());
            body.put("outputTokens", result.outputTokens());
            return Response.ok(body).build();
        } catch (RuntimeException e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("_tag", "RunFailed", "message", e.getMessage())).build();
        }
    }

    @POST
    @Path("/session/{id}/resume")
    public Response resume(@PathParam("id") String id) {
        Response blocked = ExecutionGuards.registryBlocked(engine);
        if (blocked != null) return blocked;
        Response denied = accessGuard.requireAccess(id);
        if (denied != null) return denied;
        try {
            Engine.PromptResult result = engine.resume(SessionId.parse(id));
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("text", result.text());
            body.put("finish", result.finish());
            body.put("inputTokens", result.inputTokens());
            body.put("outputTokens", result.outputTokens());
            return Response.ok(body).build();
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        } catch (IllegalStateException e) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(Map.of("_tag", "Conflict", "message", e.getMessage())).build();
        } catch (RuntimeException e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("_tag", "ResumeFailed", "message", e.getMessage())).build();
        }
    }

    @POST
    @Path("/session/{id}/prompt")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response prompt(@PathParam("id") String id, PromptRequest request) {
        Response blocked = ExecutionGuards.registryBlocked(engine);
        if (blocked != null) return blocked;
        Response denied = accessGuard.requireAccess(id);
        if (denied != null) return denied;
        if (request == null || request.text() == null || request.text().isBlank()) {
            return badRequest("text must not be blank");
        }
        Option<Schema> responseSchema = Option.none();
        if (request.responseSchema() != null) {
            Result<Schema> parsed = JsonSchema.parse(JsonValues.fromJava(request.responseSchema()));
            if (parsed instanceof Result.Err<Schema> error) {
                return badRequest("invalid responseSchema at " + error.error().path()
                        + ": " + error.error().message());
            }
            responseSchema = Option.some(parsed.valueOrNull());
        }
        return PromptResponses.run(engine, id, request.text(), responseSchema);
    }

    @POST
    @Path("/session/{id}/command")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response command(@PathParam("id") String id, CommandRequest request) {
        Response blocked = ExecutionGuards.registryBlocked(engine);
        if (blocked != null) return blocked;
        Response denied = accessGuard.requireAccess(id);
        if (denied != null) return denied;
        if (request == null || request.name() == null || request.name().isBlank()) {
            return badRequest("name must not be blank");
        }
        try {
            String arguments = request.arguments() == null ? "" : request.arguments();
            Engine.PromptResult result = engine.command(SessionId.parse(id), request.name(), arguments);
            return PromptResponses.promptResponse(result);
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
    }

    @POST
    @Path("/session/{id}/title")
    public Response title(@PathParam("id") String id) {
        Response denied = accessGuard.requireAccess(id);
        if (denied != null) return denied;
        try {
            String title = engine.generateTitle(SessionId.parse(id));
            return Response.ok(Map.of("title", title == null ? "" : title)).build();
        } catch (IllegalArgumentException e) {
            return badRequest("invalid session id");
        }
    }

    @GET
    @Path("/session/{id}/todo")
    public Response todos(@PathParam("id") String id) {
        Response denied = accessGuard.requireAccess(id);
        if (denied != null) return denied;
        try {
            return Response.ok(Map.of("data", engine.todos(SessionId.parse(id)))).build();
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
    }

    @POST
    @Path("/session/{id}/revert")
    public Response revert(@PathParam("id") String id) {
        Response denied = accessGuard.requireAccess(id);
        if (denied != null) return denied;
        try {
            de.augmentia.oc.engine.runtime.session.revert.RevertResult result =
                    engine.revert(SessionId.parse(id));
            if (result instanceof de.augmentia.oc.engine.runtime.session.revert.RevertResult.Failed failed) {
                return Response.status(422)
                        .entity(Map.of("_tag", "UnknownError", "message", failed.message())).build();
            }
            return Response.ok(Map.of("reverted", true)).build();
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        } catch (IllegalStateException e) {
            return Response.status(422)
                    .entity(Map.of("_tag", "UnknownError", "message", e.getMessage())).build();
        }
    }

    @POST
    @Path("/session/{id}/compact")
    public Response compact(@PathParam("id") String id) {
        Response denied = accessGuard.requireAccess(id);
        if (denied != null) return denied;
        try {
            de.augmentia.oc.engine.runtime.compaction.CompactionResult result =
                    engine.compact(SessionId.parse(id));
            return switch (result) {
                case de.augmentia.oc.engine.runtime.compaction.CompactionResult.Compacted compacted ->
                        Response.ok(Map.of("compacted", true, "summary", compacted.summary())).build();
                case de.augmentia.oc.engine.runtime.compaction.CompactionResult.AlreadyInProgress ignored ->
                        Response.status(409).entity(Map.of("message", "compaction already in progress")).build();
                case de.augmentia.oc.engine.runtime.compaction.CompactionResult.NothingToDo ignored ->
                        Response.ok(Map.of("compacted", false, "reason", "nothing to compact")).build();
                case de.augmentia.oc.engine.runtime.compaction.CompactionResult.Failed failed ->
                        Response.status(422).entity(Map.of("_tag", "UnknownError",
                                "message", failed.message())).build();
                case de.augmentia.oc.engine.runtime.compaction.CompactionResult.PredecessorMissing missing ->
                        Response.status(422).entity(Map.of("_tag", "CompactionError",
                                "message", missing.message())).build();
            };
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        } catch (IllegalStateException e) {
            return Response.status(422)
                    .entity(Map.of("_tag", "UnknownError", "message", e.getMessage())).build();
        }
    }

    @GET
    @Path("/session/{id}/tree")
    public Response tree(@PathParam("id") String id) {
        Response denied = accessGuard.requireAccess(id);
        if (denied != null) return denied;
        try {
            return Response.ok(engine.tree(SessionId.parse(id))).build();
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
    }

    @POST
    @Path("/session/{id}/move")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response moveSession(@PathParam("id") String id, Map<String, String> body) {
        Response denied = accessGuard.requireAccess(id);
        if (denied != null) return denied;
        if (body == null || body.get("directory") == null || body.get("directory").isBlank()) {
            return badRequest("directory must not be blank");
        }
        try {
            String result = engine.moveSession(SessionId.parse(id), body.get("directory").trim());
            return Response.ok(Map.of("directory", result)).build();
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage() == null ? "invalid session or directory" : e.getMessage());
        } catch (IllegalStateException e) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(Map.of("_tag", "SessionRunningError", "message", e.getMessage())).build();
        }
    }

    @POST
    @Path("/session/{id}/fork")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response fork(@PathParam("id") String id, ForkRequest request) {
        Response denied = accessGuard.requireAccess(id);
        if (denied != null) return denied;
        if (request == null || request.boundary() == null || request.boundary().isBlank()) {
            return badRequest("boundary must not be blank");
        }
        try {
            SessionId child = engine.fork(SessionId.parse(id), request.boundary(),
                    request.directory(), request.agent());
            return Response.ok(Map.of("id", child.value())).build();
        } catch (IllegalStateException e) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(Map.of("_tag", "SessionRunningError",
                            "message", e.getMessage() == null ? "session is running" : e.getMessage()))
                    .build();
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
    }

    @POST
    @Path("/session/{id}/interrupt")
    public Response interrupt(@PathParam("id") String id) {
        Response denied = accessGuard.requireAccess(id);
        if (denied != null) return denied;
        try {
            return Response.ok(Map.of("interrupted", engine.interrupt(SessionId.parse(id)))).build();
        } catch (IllegalArgumentException e) {
            return badRequest("invalid session id");
        }
    }

    @GET
    @Path("/session/{id}/running")
    public Response running(@PathParam("id") String id) {
        Response denied = accessGuard.requireAccess(id);
        if (denied != null) return denied;
        try {
            return Response.ok(Map.of("running", engine.isRunning(SessionId.parse(id)))).build();
        } catch (IllegalArgumentException e) {
            return badRequest("invalid session id");
        }
    }

    /**
     * Registry instances serve the agent catalogue and authoring API only.
     * Execution endpoints are rejected so an accidentally exposed registry
     * cannot run prompts.
     */
    private static Response badRequest(String message) {
        return Response.status(Response.Status.BAD_REQUEST)
                .type(MediaType.APPLICATION_JSON)
                .entity(Map.of("_tag", "InvalidRequestError",
                        "message", message == null ? "invalid request" : message))
                .build();
    }

}
