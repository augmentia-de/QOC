package de.augmentia.oc.engine.http;

import de.augmentia.oc.engine.core.model.SessionId;
import de.augmentia.oc.engine.core.contract.permission.PermissionRequest;
import de.augmentia.oc.engine.core.contract.permission.Reply;
import de.augmentia.oc.engine.core.json.JsonSchema;
import de.augmentia.oc.engine.core.json.JsonValues;
import de.augmentia.oc.engine.core.json.Result;
import de.augmentia.oc.engine.core.json.Schema;
import de.augmentia.oc.engine.core.model.PermissionRequestId;
import de.augmentia.oc.engine.core.model.Option;
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

    @Inject
    EngineMetrics metrics;

    @Inject
    de.augmentia.oc.engine.http.security.PrincipalContext principalContext;

    @GET
    @Path("/health/live")
    public Response live() {
        return Response.ok(Map.of("status", "UP", "check", "live")).build();
    }

    @GET
    @Path("/health/ready")
    public Response ready() {
        boolean ready = engine.ready();
        return Response.status(ready ? Response.Status.OK : Response.Status.SERVICE_UNAVAILABLE)
                .entity(Map.of("status", ready ? "UP" : "DOWN", "check", "ready"))
                .build();
    }

    @GET
    @Path("/metrics")
    public Response metrics() {
        return Response.ok(metrics.snapshot()).build();
    }

    @GET
    @Path("/audit")
    public Response audit(@QueryParam("session") String session,
                          @QueryParam("limit") int limit) {
        if (session == null || session.isBlank()) {
            return badRequest("session must not be blank");
        }
        try {
            return Response.ok(Map.of("data",
                    engine.audit(SessionId.parse(session), limit))).build();
        } catch (IllegalArgumentException e) {
            return badRequest("invalid session id");
        }
    }

    @POST
    @Path("/session")
    public Response createSession(CreateSessionRequest request,
                                 @HeaderParam("X-QOC-User") String user) {
        String principalUser = user;
        if ((principalUser == null || principalUser.isBlank()) && principalContext != null) {
            var principal = principalContext.get();
            if (principal != null && principal.id() != null && !principal.id().isBlank()) {
                principalUser = principal.id();
            }
        }
        SessionId id = engine.createSession(request == null ? null : request.agent(), principalUser);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", id.value());
        engine.session(id).ifPresent(info -> body.put("data", dto(info)));
        return Response.ok(body).build();
    }

    @POST
    @Path("/session/{id}/agent")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response switchAgent(@PathParam("id") String id, SwitchAgentRequest request) {
        if (request == null || request.agent() == null || request.agent().isBlank()) {
            return badRequest("agent must not be blank");
        }
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
        String current = currentPrincipal();
        List<?> data = engine.listSessions().stream()
                .filter(info -> isOwner(info, current))
                .map(HttpApi::dto)
                .toList();
        return Response.ok(Map.of("data", data))
                .build();
    }

    @GET
    @Path("/session/{id}")
    public Response session(@PathParam("id") String id) {
        try {
            var info = engine.session(SessionId.parse(id));
            if (info.isEmpty()) return Response.status(Response.Status.NOT_FOUND).build();
            String current = currentPrincipal();
            if (!isOwner(info.orElseThrow(), current)) {
                return Response.status(Response.Status.FORBIDDEN).build();
            }
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
        Option<Schema> responseSchema = Option.none();
        if (request.responseSchema() != null) {
            Result<Schema> parsed = JsonSchema.parse(JsonValues.fromJava(request.responseSchema()));
            if (parsed instanceof Result.Err<Schema> error) {
                return badRequest("invalid responseSchema at " + error.error().path()
                        + ": " + error.error().message());
            }
            responseSchema = Option.some(parsed.valueOrNull());
        }
        return runPrompt(id, request.text(), responseSchema);
    }

    @POST
    @Path("/session/{id}/command")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response command(@PathParam("id") String id, CommandRequest request) {
        if (request == null || request.name() == null || request.name().isBlank()) {
            return badRequest("name must not be blank");
        }
        try {
            String arguments = request.arguments() == null ? "" : request.arguments();
            Engine.PromptResult result = engine.command(SessionId.parse(id), request.name(), arguments);
            return promptResponse(result);
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
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

    @GET
    @Path("/session/{id}/todo")
    public Response todos(@PathParam("id") String id) {
        try {
            return Response.ok(Map.of("data", engine.todos(SessionId.parse(id)))).build();
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
    }

    @GET
    @Path("/session/{id}/question")
    public Response pendingQuestion(@PathParam("id") String id) {
        try {
            var pending = engine.pendingQuestion(SessionId.parse(id));
            if (pending.isEmpty()) {
                return Response.ok(Map.of("id", "", "questions", List.of())).build();
            }
            var question = pending.orElseThrow();
            return Response.ok(Map.of("id", question.id(), "questions", question.questions())).build();
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
    }

    @POST
    @Path("/session/{id}/question/{questionId}/reply")
    public Response replyQuestion(@PathParam("id") String id,
                                  @PathParam("questionId") String questionId,
                                  QuestionReply request) {
        try {
            boolean resolved = engine.replyQuestion(SessionId.parse(id), questionId,
                    request == null ? List.of() : request.selected(),
                    request == null ? "" : request.text());
            if (!resolved) {
                return Response.status(404).entity(Map.of("message", "question not found")).build();
            }
            return Response.ok(Map.of("ok", true)).build();
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
    }

    @POST
    @Path("/session/{id}/revert")
    public Response revert(@PathParam("id") String id) {        try {
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
    @Path("/session/{id}/processes")
    public Response processes(@PathParam("id") String id) {
        try {
            List<Map<String, Object>> data = engine.processes(SessionId.parse(id)).stream()
                    .map(HttpApi::processDto).toList();
            return Response.ok(Map.of("data", data)).build();
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
    }

    @POST
    @Path("/session/{id}/processes/{processId}/kill")
    public Response killProcess(@PathParam("id") String id, @PathParam("processId") String processId) {
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
        try {
            List<Map<String, Object>> data = engine.tasks(SessionId.parse(id)).stream()
                    .map(HttpApi::taskDto).toList();
            return Response.ok(Map.of("data", data)).build();
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
    }

    @POST
    @Path("/session/{id}/tasks/{taskId}/cancel")
    public Response cancelTask(@PathParam("id") String id, @PathParam("taskId") String taskId) {
        try {
            boolean cancelled = engine.cancelTask(SessionId.parse(id), taskId);
            return Response.ok(Map.of("cancelled", cancelled)).build();
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
    }

    @GET
    @Path("/session/{id}/tree")
    public Response tree(@PathParam("id") String id) {
        try {
            return Response.ok(engine.tree(SessionId.parse(id))).build();
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
    }

    @POST
    @Path("/session/{id}/fork")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response fork(@PathParam("id") String id, ForkRequest request) {
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
        try {
            return Response.ok(Map.of("interrupted", engine.interrupt(SessionId.parse(id)))).build();
        } catch (IllegalArgumentException e) {
            return badRequest("invalid session id");
        }
    }


    private Map<String, Object> permissionDto(PermissionRequest r) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("id", r.id().value());
        m.put("sessionId", r.sessionId().value());
        m.put("action", r.action());
        m.put("resources", r.resources());
        m.put("save", r.save());
        m.put("message", r.message());
        return m;
    }

    @GET
    @Path("/session/{id}/permission")
    public Response sessionPermissions(@PathParam("id") String id) {
        try {
            List<PermissionRequest> pending = engine.pendingPermissions(SessionId.parse(id));
            List<Map<String, Object>> data = pending.stream().map(this::permissionDto).toList();
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

    @GET
    @Path("/permission/request")
    public Response openPermissions() {
        List<Map<String, Object>> data = engine.allPendingPermissions().stream().map(this::permissionDto).toList();
        return Response.ok(Map.of("location", engine.location(), "data", data)).build();
    }

    @GET
    @Path("/files")
    public Response files(@QueryParam("path") String path) {
        try {
            return Response.ok(Map.of("data", engine.listFiles(path))).build();
        } catch (RuntimeException e) {
            return badRequest(e.getMessage());
        }
    }

    @GET
    @Path("/file")
    public Response file(@QueryParam("path") String path) {
        try {
            String content = engine.readFile(path);
            return Response.ok(Map.of("data", Map.of("path", path, "content", content))).build();
        } catch (RuntimeException e) {
            return badRequest(e.getMessage());
        }
    }

    @GET
    @Path("/file/download")
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    public Response downloadFile(@QueryParam("path") String path) {
        try {
            byte[] content = engine.readFileBytes(path);
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

    @GET
    @Path("/mcp")
    public Response mcpServers() {
        return Response.ok(Map.of("data", engine.mcpServerNames())).build();
    }

    @GET
    @Path("/mcp/{server}/status")
    public Response mcpStatus(@PathParam("server") String server) {
        String error = engine.mcpError(server);
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("status", engine.mcpStatus(server));
        if (error != null && !error.isBlank()) body.put("error", error);
        return Response.ok(body).build();
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
    public Response skills(@QueryParam("all") @DefaultValue("false") boolean all) {
        return Response.ok(Map.of("location", engine.location(), "data", engine.skills(all))).build();
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

    @POST
    @Path("/config/reload")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response reloadConfig(@HeaderParam("X-QOC-User") String user) {
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

    @POST
    @Path("/file/upload")
    @Consumes(MediaType.APPLICATION_OCTET_STREAM)
    public Response uploadFile(@QueryParam("path") String path, byte[] body) {
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
            engine.deletePath(path);
            return Response.ok(Map.of("ok", true)).build();
        } catch (RuntimeException e) {
            return badRequest(e.getMessage());
        }
    }

    private static String fileName(String path) {
        try {
            java.nio.file.Path name = java.nio.file.Path.of(path == null ? "" : path).getFileName();
            return name == null ? "download"
                    : name.toString().replace("\"", "").replace("\r", "").replace("\n", "");
        } catch (RuntimeException e) {
            return "download";
        }
    }

    private static String attachment(String path) {
        return "attachment; filename=\"" + fileName(path) + "\"";
    }

    @GET
    @Path("/location")
    public Engine.LocationDto location() {
        return engine.location();
    }

    private Response runPrompt(String id, String text) {
        return runPrompt(id, text, Option.none());
    }

    private Response runPrompt(String id, String text, Option<Schema> responseSchema) {
        try {
            return promptResponse(engine.prompt(SessionId.parse(id), text, responseSchema));
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        } catch (IllegalStateException e) {
            return Response.status(422).entity(Map.of("_tag", "UnknownError", "message", e.getMessage())).build();
        }
    }

    private Response promptResponse(Engine.PromptResult result) {
        Object structured = result.structured().map(JsonValues::toJava).orElse(null);
        if (result.outcome() instanceof Engine.TurnOutcome.Busy busy) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(Map.of("_tag", "SessionBusyError",
                            "outcome", "BUSY",
                            "message", busy.message() == null ? "session is running" : busy.message()))
                    .build();
        }
        if (result.outcome() instanceof Engine.TurnOutcome.Refused refused) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("_tag", "TurnRefusedError");
            body.put("outcome", "REFUSED");
            body.put("reason", refused.reason());
            body.put("message", refused.detail());
            return Response.status(422).entity(body).build();
        }
        if (result.outcome() instanceof Engine.TurnOutcome.Failed failed) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("_tag", "TurnFailedError");
            body.put("outcome", "FAILED");
            body.put("retryable", failed.retryable());
            body.put("error", failed.error().type());
            body.put("message", failed.error().message());
            return Response.status(failed.retryable() ? 503 : 502).entity(body).build();
        }
        if (result.outcome() instanceof Engine.TurnOutcome.Terminated terminated) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(Map.of("_tag", "TurnTerminatedError",
                            "outcome", "TERMINATED",
                            "reason", terminated.reason().name()))
                    .build();
        }
        if (result.outcome() instanceof Engine.TurnOutcome.Cancelled) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(Map.of("_tag", "TurnCancelledError",
                            "outcome", "CANCELLED",
                            "message", "turn was cancelled"))
                    .build();
        }
        return Response.ok(new PromptResponse(outcomeName(result.outcome()),
                result.finish(), result.inputTokens(), result.outputTokens(),
                result.text(), structured)).build();
    }

    private static String outcomeName(Engine.TurnOutcome outcome) {
        return switch (outcome) {
            case Engine.TurnOutcome.Completed ignored -> "COMPLETED";
            case Engine.TurnOutcome.Busy ignored -> "BUSY";
            case Engine.TurnOutcome.Refused ignored -> "REFUSED";
            case Engine.TurnOutcome.Failed ignored -> "FAILED";
            case Engine.TurnOutcome.Terminated ignored -> "TERMINATED";
            case Engine.TurnOutcome.Cancelled ignored -> "CANCELLED";
        };
    }

    private static Response badRequest(String message) {
        return Response.status(Response.Status.BAD_REQUEST)
                .type(MediaType.APPLICATION_JSON)
                .entity(Map.of("_tag", "InvalidRequestError",
                        "message", message == null ? "invalid request" : message))
                .build();
    }

    private static Map<String, Object> processDto(
            de.augmentia.oc.engine.runtime.tool.shell.ProcessRegistry.Snapshot process) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id", process.id());
        dto.put("state", process.state().name().toLowerCase());
        dto.put("command", process.command());
        dto.put("stdout", process.stdout());
        dto.put("stderr", process.stderr());
        process.exit().ifPresent(exit -> dto.put("exit", exit));
        dto.put("started", process.started().toString());
        if (process.finished() != null) dto.put("finished", process.finished().toString());
        return dto;
    }

    private static Map<String, Object> taskDto(
            de.augmentia.oc.engine.runtime.tool.subagent.BackgroundTasks.Snapshot task) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id", task.id());
        dto.put("state", task.state().name().toLowerCase());
        dto.put("agent", task.agent());
        dto.put("text", task.text());
        dto.put("error", task.error());
        task.childSession().ifPresent(child -> dto.put("childSession", child.value()));
        dto.put("tokens", task.usage().total());
        dto.put("created", task.created().toString());
        if (task.finished() != null) dto.put("finished", task.finished().toString());
        return dto;
    }

    private static SessionDto dto(SessionInfo session) {
        return new SessionDto(session.id().value(), session.title(), session.agent().value(),
                session.location().directory().toString(), session.cost().toString(),
                session.tokens().total());
    }

    private static MessageDto message(de.augmentia.oc.engine.core.model.Message message) {
        return new MessageDto(message.id().value(), message.getClass().getSimpleName(), text(message));
    }

    public record PromptRequest(String text, Map<String, Object> responseSchema) {
        public PromptRequest(String text) {
            this(text, null);
        }
    }

    public record CreateSessionRequest(String agent) {}

    public record SwitchAgentRequest(String agent) {}

    private String currentPrincipal() {
        if (principalContext == null) return null;
        var principal = principalContext.get();
        if (principal == null || principal.id() == null || principal.id().isBlank()) return null;
        return principal.id();
    }

    private boolean isOwner(SessionInfo info, String current) {
        if (current == null || current.isBlank()) {
            return false;
        }
        return true;
    }

    public record CommandRequest(String name, String arguments) {}

    public record ForkRequest(String boundary, String directory, String agent) {}

    public record QuestionReply(List<String> selected, String text) {}

    public record PromptResponse(String outcome, String finish, long inputTokens,
                                 long outputTokens, String text, Object structured) {}

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
