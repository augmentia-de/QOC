package de.augmentia.oc.engine.http;

import de.augmentia.oc.engine.core.eval.PermissionRules;
import de.augmentia.oc.engine.core.model.Effect;
import de.augmentia.oc.engine.core.model.Rule;
import de.augmentia.oc.engine.runtime.context.ContextBuilder;
import de.augmentia.qoc.workflowrunner.model.WorkflowDefinition.Agent;
import de.augmentia.qoc.workflowrunner.store.AgentStorePort;

import java.util.ArrayList;
import java.util.List;

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
// Authorization is enforced manually via SessionAccessGuard (see AgentResource pattern).
public class AgentStoreResource {

    /** File names are derived from the id; keep it a plain slug. */
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

    @Inject
    AgentStorePort store;

    @Inject
    SessionAccessGuard accessGuard;

    @Inject
    jakarta.enterprise.inject.Instance<Engine> engineInstance;

    @GET
    public Response list() {
        String user = accessGuard == null ? null : accessGuard.currentUser();
        boolean admin = accessGuard != null && accessGuard.isAdmin();
        return Response.ok(Map.of("agents", store.listAll(user, admin))).build();
    }

    @GET
    @Path("/{id}")
    public Response get(@PathParam("id") String id) {
        if (!valid(id)) return badRequest("invalid agent id");
        Agent agent = store.get(id);
        if (agent == null) return Response.status(Response.Status.NOT_FOUND)
                .entity(Map.of("error", "agent not found: " + id)).build();
        Response denied = accessGuard == null ? null : accessGuard.requireAgentAccess(agent.ownerId());
        if (denied != null) return denied;
        return Response.ok(agent).build();
    }

    @POST
    public Response create(Agent body) {
        if (body == null) return badRequest("agent must not be null");
        String id = body.id();
        if (id == null || id.isBlank()) id = slug(body.name());
        if ("build".equalsIgnoreCase(id) || "plan".equalsIgnoreCase(id)) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", "built-in agent '" + id + "' cannot be created")).build();
        }
        if (!valid(id)) return badRequest("invalid agent id");
        String owner = accessGuard == null ? null : accessGuard.currentUser();
        Agent saved = store.save(id, withId(body, id, owner));
        // SSOT: best-effort export to OpenCode-compatible Markdown.
        try { AgentMarkdownExporter.write(AgentMarkdownLoader.globalAgentDir(), saved); }
        catch (RuntimeException ignored) {}
        // SSOT: propagate to QOC engine (best-effort).
        try { if (engineInstance != null && engineInstance.isResolvable()) engineInstance.get().reloadConfig(); }
        catch (RuntimeException ignored) {}
        return Response.status(Response.Status.CREATED).entity(saved).build();
    }

    @PUT
    @Path("/{id}")
    public Response save(@PathParam("id") String id, Agent body) {
        if (!valid(id)) return badRequest("invalid agent id");
        if ("build".equalsIgnoreCase(id) || "plan".equalsIgnoreCase(id)) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", "built-in agent '" + id + "' cannot be edited")).build();
        }
        if (body == null) return badRequest("agent must not be null");
        Agent existing = store.get(id);
        if (existing != null) {
            Response denied = accessGuard == null ? null : accessGuard.requireAgentAccess(existing.ownerId());
            if (denied != null) return denied;
        }
        // The edit form exposes the id; a changed id is a rename.
        String target = body.id();
        if (target != null && !target.isBlank() && !target.equals(id)) {
            if (!valid(target)) return badRequest("invalid agent id");
            store.delete(id);
            Agent saved = store.save(target, withId(body, target));
            try { AgentMarkdownExporter.write(AgentMarkdownLoader.globalAgentDir(), saved); }
            catch (RuntimeException ignored) {}
            try { if (engineInstance != null && engineInstance.isResolvable()) engineInstance.get().reloadConfig(); }
            catch (RuntimeException ignored) {}
            return Response.ok(saved).build();
        }
        String owner = existing == null ? (accessGuard == null ? null : accessGuard.currentUser()) : null;
        Agent saved = store.save(id, withId(body, id, owner));
        // SSOT: best-effort export to OpenCode-compatible Markdown.
        try { AgentMarkdownExporter.write(AgentMarkdownLoader.globalAgentDir(), saved); }
        catch (RuntimeException ignored) {}
        // SSOT: propagate to QOC engine (best-effort).
        try { if (engineInstance != null && engineInstance.isResolvable()) engineInstance.get().reloadConfig(); }
        catch (RuntimeException ignored) {}
        return Response.ok(saved).build();
    }

    @POST
    @Path("/permission-check")
    public Response permissionCheck(Agent body) {
        if (body == null) return badRequest("agent must not be null");
        List<String> tools = body.tools() == null ? List.of() : body.tools();
        List<String> denied = new ArrayList<>();
        List<String> ask = new ArrayList<>();
        List<Rule> rules;
        try {
            rules = new ArrayList<>(ContextBuilder.defaults(EngineConfig.permissionDefault()));
            if (body.permissions() != null) rules.addAll(body.permissions());
        } catch (RuntimeException e) {
            rules = List.of();
        }
        for (String t : tools) {
            if (t == null || t.isBlank()) continue;
            Effect eff = PermissionRules.evaluate(rules, t, "*");
            if (eff == Effect.DENY) denied.add(t);
            else if (eff == Effect.ASK) ask.add(t);
        }
        return Response.ok(Map.of("deniedTools", denied, "askTools", ask,
                "permissionDefault", EngineConfig.permissionDefault())).build();
    }

    @DELETE
    @Path("/{id}")
    public Response delete(@PathParam("id") String id) {
        if (!valid(id)) return badRequest("invalid agent id");
        if ("build".equalsIgnoreCase(id) || "plan".equalsIgnoreCase(id)) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", "built-in agent '" + id + "' cannot be deleted")).build();
        }
        Agent existing = store.get(id);
        if (existing != null) {
            Response denied = accessGuard == null ? null : accessGuard.requireAgentAccess(existing.ownerId());
            if (denied != null) return denied;
        }
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
        return withId(a, id, a.ownerId());
    }

    private static Agent withId(Agent a, String id, String owner) {
        String ownerId = (owner == null || owner.isBlank()) ? a.ownerId() : owner;
        return new Agent(id, a.name(), a.version(), a.category(), a.model(), a.temperature(),
                a.maxTokens(), a.topP(), a.tools(), a.guardrailsInput(), a.guardrailsOutput(),
                a.hooks(), a.jsonOutput(), a.active(), a.systemPrompt(), a.responseSchema(),
                ownerId == null ? "" : ownerId, a.rolesAllowed(),
                a.mode() == null ? "all" : a.mode(),
                a.skills() == null ? java.util.List.of() : a.skills(),
                a.maxTurns(), a.maxCost(), a.requiresHITL(),
                a.permissions() == null ? java.util.List.of() : a.permissions());
    }

    private static Response badRequest(String message) {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(Map.of("error", message)).build();
    }
}
