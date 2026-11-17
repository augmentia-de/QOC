package de.augmentia.oc.engine.http;

import jakarta.annotation.security.PermitAll;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Response;

import java.net.URI;

@Path("/workflow")
@PermitAll
public class WorkflowRedirectResource {

    @GET
    public Response root() {
        return Response.temporaryRedirect(URI.create("/")).build();
    }

    @GET
    @Path("{path:.*}")
    public Response redirect(@PathParam("path") String path) {
        String target = "/";
        if (path != null && !path.isBlank()) {
            target = "/" + path;
        }
        return Response.temporaryRedirect(URI.create(target)).build();
    }
}