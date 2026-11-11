package de.augmentia.oc.engine.http;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Map;

/** Shared guards for execution endpoints (H7). */
final class ExecutionGuards {

    private ExecutionGuards() {}

    /**
     * Registry instances serve the agent catalogue and authoring API only.
     * Execution endpoints are rejected so an accidentally exposed registry
     * cannot run prompts.
     */
    static Response registryBlocked(Engine engine) {
        if (engine == null || !engine.isRegistryMode()) {
            return null;
        }
        return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                .type(MediaType.APPLICATION_JSON)
                .entity(Map.of("_tag", "RegistryMode",
                        "message", "execution is disabled in registry role; use the agent API"))
                .build();
    }
}
