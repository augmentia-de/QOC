package de.augmentia.oc.engine.http;

import jakarta.annotation.security.PermitAll;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.io.InputStream;
import java.util.Locale;

@Path("/")
@PermitAll
public class RootSpaFallbackResource {

    @GET
    @Produces(MediaType.TEXT_HTML)
    public Response root() {
        return serveIndex();
    }

    @GET
    @Path("{path:.*}")
    @Produces(MediaType.TEXT_HTML)
    public Response spa(@PathParam("path") String path) {
        String normalized = path == null ? "" : path;
        if (normalized.startsWith("/")) normalized = normalized.substring(1);

        // Never shadow API, Quarkus, Core-UI or workflow redirects.
        if (normalized.startsWith("api/")
                || normalized.startsWith("core/")
                || normalized.equals("core")
                || normalized.startsWith("q/")
                || normalized.startsWith("assets/")
                || normalized.startsWith("workflow/")
                || normalized.equals("workflow")
                || normalized.equals("config.js")
                || normalized.equals("health")
                || normalized.equals("metrics")
                || normalized.equals("audit")) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        // Let Quarkus serve real static files (hashed assets, icons, manifests).
        // React deep links such as /workflows/customer.onboarding must still reach
        // the SPA, so only known file extensions are treated as static assets.
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (lower.matches(".*\\.(js|css|svg|png|jpg|jpeg|gif|ico|woff2?|ttf|eot|map|json|webmanifest|txt)$")) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        return serveIndex();
    }

    private Response serveIndex() {
        InputStream is = getClass().getResourceAsStream("/META-INF/resources/index.html");
        if (is == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return Response.ok(is).type(MediaType.TEXT_HTML_TYPE).build();
    }
}