package de.augmentia.oc.engine.http;

import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Map;

/**
 * Discovery API for the configured external A2A agents.
 *
 * <p>The endpoint is protected by the same authentication rules as the rest of
 * {@code /api}. It performs no network calls when all cached cards are fresh;
 * use {@code ?refresh=true} or {@code POST /refresh} to force a reload.</p>
 */
@Path("/api/a2a/agents")
@Produces(MediaType.APPLICATION_JSON)
public class A2AAgentsResource {

    @Inject
    A2AConfig config;

    @GET
    public Response list(@QueryParam("refresh") @DefaultValue("false") boolean refresh) {
        return Response.ok(Map.of("data", config.discover(refresh)))
                .header("Cache-Control", "no-store")
                .build();
    }

    @POST
    @Path("/refresh")
    public Response refresh() {
        return Response.ok(Map.of("data", config.discover(true)))
                .header("Cache-Control", "no-store")
                .build();
    }
}
