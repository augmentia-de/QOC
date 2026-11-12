package de.augmentia.oc.engine.http;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.annotation.security.PermitAll;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.io.InputStream;

@Path("/workflow")
@PermitAll
public class SpaFallbackResource {

    @GET
    @Produces(MediaType.TEXT_HTML)
    public Response spaRoot() {
        java.util.logging.Logger.getLogger("SpaFallback").info("spaRoot called for /workflow");
        return serveIndex();
    }

    @GET
    @Path("{path:.*}")
    @Produces(MediaType.TEXT_HTML)
    public Response spa(@PathParam("path") String path) {
        // Let Quarkus serve static assets (js/css/fonts) from
        // META-INF/resources/workflow/ with their proper MIME types.
        if (path != null && path.contains(".")) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return serveIndex();
    }

    private Response serveIndex() {
        InputStream is = getClass().getResourceAsStream("/META-INF/resources/workflow/index.html");
        if (is == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return Response.ok(is).type(MediaType.TEXT_HTML_TYPE).build();
    }
}
