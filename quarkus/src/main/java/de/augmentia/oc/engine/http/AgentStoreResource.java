package de.augmentia.oc.engine.http;

import de.augmentia.qoc.workflowrunner.model.WorkflowDefinition.Agent;
import de.augmentia.qoc.workflowrunner.store.AgentStore;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Global agent store (Option B).
 *
 * <p>Workflows reference agents by id ({@code agentRef}); the store persists
 * them as JSON files next to the workflow definitions so the UI can manage
 * them independently from a workflow.</p>
 */
@Path("/api/agents")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AgentStoreResource {

    /** File names are derived from the id; keep it a plain slug. */
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

    @Inject
    AgentStore store;

    @GET
    public Response list() {
        return Response.ok(Map.of("agents", store.listAll())).build();
    }

    @GET
    @Path("/{id}")
    public Response get(@PathParam("id") String id) {
        if (!valid(id)) return badRequest("invalid agent id");
        Agent agent = store.get(id);
        return agent == null
                ? Response.status(Response.Status.NOT_FOUND)
                        .entity(Map.of("error", "agent not found: " + id)).build()
                : Response.ok(agent).build();
    }

    @POST
    public Response create(Agent body) {
        if (body == null) return badRequest("agent must not be null");
        String id = body.id();
        if (id == null || id.isBlank()) id = slug(body.name());
        if (!valid(id)) return badRequest("invalid agent id");
        return Response.status(Response.Status.CREATED).entity(store.save(id, withId(body, id))).build();
    }

    @PUT
    @Path("/{id}")
    public Response save(@PathParam("id") String id, Agent body) {
        if (!valid(id)) return badRequest("invalid agent id");
        if (body == null) return badRequest("agent must not be null");
        // The edit form exposes the id; a changed id is a rename.
        String target = body.id();
        if (target != null && !target.isBlank() && !target.equals(id)) {
            if (!valid(target)) return badRequest("invalid agent id");
            store.delete(id);
            return Response.ok(store.save(target, withId(body, target))).build();
        }
        return Response.ok(store.save(id, withId(body, id))).build();
    }

    @DELETE
    @Path("/{id}")
    public Response delete(@PathParam("id") String id) {
        if (!valid(id)) return badRequest("invalid agent id");
        return Response.ok(Map.of("deleted", store.delete(id))).build();
    }

    private static boolean valid(String id) {
        return id != null && ID.matcher(id).matches();
    }

    private static String slug(String name) {
        String base = name == null ? "" : name.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        return base.isBlank() ? "agent-" + System.currentTimeMillis() : base;
    }

    private static Agent withId(Agent a, String id) {
        return new Agent(id, a.name(), a.version(), a.category(), a.model(), a.temperature(),
                a.maxTokens(), a.topP(), a.tools(), a.guardrailsInput(), a.guardrailsOutput(),
                a.hooks(), a.jsonOutput(), a.active(), a.systemPrompt(), a.responseSchema());
    }

    private static Response badRequest(String message) {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(Map.of("error", message)).build();
    }
}
