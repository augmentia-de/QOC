package de.augmentia.oc.engine.http;

import com.fasterxml.jackson.databind.ObjectMapper;

import de.augmentia.qoc.workflowrunner.model.WorkflowDefinition;
import de.augmentia.qoc.workflowrunner.runner.WorkflowExecution;
import de.augmentia.qoc.workflowrunner.runner.WorkflowExecutor;
import de.augmentia.qoc.workflowrunner.runner.WorkflowRunJournal;
import de.augmentia.qoc.workflowrunner.runner.WorkflowRunSnapshot;
import de.augmentia.qoc.workflowrunner.store.WorkflowDefinitionStore;
import de.augmentia.qoc.workflowrunner.validation.GraphCompiler;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Path("/api")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class WorkflowResource {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject
    Engine engine;

    @Inject
    WorkflowDefinitionStore store;

    @Inject
    de.augmentia.qoc.workflowrunner.store.AgentStore agentStore;

    @GET
    @Path("/workflows")
    public Response listWorkflows() {
        return Response.ok(Map.of("workflows", store.list())).build();
    }

    @POST
    @Path("/workflows")
    public Response createWorkflow(Map<String, Object> request) {
        String name = request == null ? "Untitled" : String.valueOf(request.getOrDefault("name", "Untitled"));
        WorkflowDefinition def = store.create(name);
        return Response.status(Response.Status.CREATED).entity(def).build();
    }

    @GET
    @Path("/workflows/{id}")
    public Response getWorkflow(@PathParam("id") String id) {
        WorkflowDefinition def = store.get(id);
        if (def == null) return Response.status(Response.Status.NOT_FOUND).entity(Map.of("error", "not found")).build();
        return Response.ok(def).build();
    }

    @PUT
    @Path("/workflows/{id}")
    public Response saveWorkflow(@PathParam("id") String id, WorkflowDefinition def) {
        WorkflowDefinition saved = store.save(id, def);
        return Response.ok(saved).build();
    }

    @DELETE
    @Path("/workflows/{id}")
    public Response deleteWorkflow(@PathParam("id") String id) {
        boolean deleted = store.delete(id);
        return Response.ok(Map.of("deleted", deleted)).build();
    }

    @POST
    @Path("/workflows/validate")
    public Response validate(WorkflowDefinition def) {
        var result = GraphCompiler.validate(def, knownAgentIds());
        return Response.ok(Map.of("valid", result.valid(), "errors", result.errors(), "summary", result.summary() == null ? "" : result.summary())).build();
    }

    @GET
    @Path("/workflows/{id}/versions")
    public Response versions(@PathParam("id") String id) {
        return Response.ok(Map.of("versions", store.versions(id))).build();
    }

    @GET
    @Path("/workflows/{id}/versions/{version}")
    public Response version(@PathParam("id") String id, @PathParam("version") String version) {
        WorkflowDefinition def = store.version(id, version);
        if (def == null) return Response.status(Response.Status.NOT_FOUND).entity(Map.of("error", "not found")).build();
        return Response.ok(def).build();
    }

    @POST
    @Path("/workflows/run")
    public Response run(Map<String, Object> request) {
        try {
            WorkflowDefinition def = MAPPER.convertValue(request.get("workflow"), WorkflowDefinition.class);
            String input = request == null ? "" : String.valueOf(request.getOrDefault("input", ""));
            var validation = GraphCompiler.validate(def, knownAgentIds());
            if (!validation.valid()) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(Map.of("valid", false, "errors", validation.errors())).build();
            }
            WorkflowExecutor executor = executor();
            WorkflowExecution execution = executor.executeRun(def, input);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("result", execution.result());
            body.put("valid", true);
            body.putAll(executionFields(execution));
            return Response.ok(body).build();
        } catch (Exception e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", e.getMessage())).build();
        }
    }

    @GET
    @Path("/workflows/runs")
    public Response listRuns(@QueryParam("limit") @DefaultValue("50") int limit) {
        WorkflowRunJournal journal = new WorkflowRunJournal(engine.qocEngine());
        return Response.ok(Map.of("runs", journal.scan(limit))).build();
    }

    @GET
    @Path("/workflows/runs/{runId}")
    public Response runStatus(@PathParam("runId") String runId) {
        WorkflowRunJournal journal = new WorkflowRunJournal(engine.qocEngine());
        Optional<WorkflowRunSnapshot> snapshot = journal.load(runId);
        if (snapshot.isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND).entity(Map.of("error", "run not found")).build();
        }
        return Response.ok(snapshot.get()).build();
    }

    @POST
    @Path("/workflows/runs/{runId}/resume")
    @Consumes(MediaType.WILDCARD)
    public Response resumeRun(@PathParam("runId") String runId) {
        try {
            WorkflowExecution execution = executor().resume(runId);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("result", execution.result());
            body.put("valid", true);
            body.putAll(executionFields(execution));
            return Response.ok(body).build();
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", e.getMessage())).build();
        } catch (IllegalStateException e) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(Map.of("error", e.getMessage())).build();
        }
    }

    private WorkflowExecutor executor() {
        return new WorkflowExecutor(engine.qocEngine(), store, engine.mcp().manager(), agentStore);
    }

    /** Global agents may be referenced from workflow nodes (Option B). */
    private java.util.Set<String> knownAgentIds() {
        return new java.util.HashSet<>(agentStore.list());
    }

    private static Map<String, Object> executionFields(WorkflowExecution execution) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("runId", execution.runId());
        fields.put("sessionId", execution.sessionId());
        fields.put("finish", execution.finish());
        fields.put("failed", execution.failed());
        fields.put("paused", execution.paused());
        if (execution.error() != null) fields.put("error", execution.error());
        return fields;
    }
}
