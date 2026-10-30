package de.augmentia.qoc.infratest;

import java.net.URI;
import java.util.List;

import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

/**
 * CRUD REST resource for the single {@code infratest_entry} table.
 *
 * <pre>
 * GET    /infratest        list all entries
 * GET    /infratest/{id}   read one entry by primary key
 * POST   /infratest        create an entry
 * PUT    /infratest/{id}   replace name and content of an entry
 * DELETE /infratest/{id}   delete an entry
 * </pre>
 */
@Path("/infratest")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class InfratestResource {

    private static final int MAX_NAME_LENGTH = 255;
    private static final int MAX_CONTENT_LENGTH = 1024;

    private final InfratestRepository repository;

    public InfratestResource(InfratestRepository repository) {
        this.repository = repository;
    }

    @GET
    public List<InfratestResponse> list() {
        return repository.listOrderedById().stream()
                .map(InfratestResponse::from)
                .toList();
    }

    @GET
    @Path("{id}")
    public InfratestResponse get(@PathParam("id") long id) {
        return repository.findByIdOptional(id)
                .map(InfratestResponse::from)
                .orElseThrow(() -> notFound(id));
    }

    @POST
    public Response create(InfratestRequest request, @Context UriInfo uriInfo) {
        ValidatedRequest validated = validate(request);
        InfratestEntry created = repository.create(validated.name(), validated.content());
        URI location = uriInfo.getAbsolutePathBuilder()
                .path(String.valueOf(created.getId()))
                .build();
        return Response.created(location)
                .entity(InfratestResponse.from(created))
                .build();
    }

    @PUT
    @Path("{id}")
    public InfratestResponse update(@PathParam("id") long id, InfratestRequest request) {
        ValidatedRequest validated = validate(request);
        return repository.update(id, validated.name(), validated.content())
                .map(InfratestResponse::from)
                .orElseThrow(() -> notFound(id));
    }

    @DELETE
    @Path("{id}")
    public Response delete(@PathParam("id") long id) {
        if (!repository.delete(id)) {
            throw notFound(id);
        }
        return Response.noContent().build();
    }

    private static ValidatedRequest validate(InfratestRequest request) {
        if (request == null) {
            throw new BadRequestException("request body is required");
        }
        if (request.name() == null || request.name().isBlank()) {
            throw new BadRequestException("name must not be blank");
        }
        String name = request.name().strip();
        if (name.length() > MAX_NAME_LENGTH) {
            throw new BadRequestException("name must not exceed " + MAX_NAME_LENGTH + " characters");
        }
        if (request.content() != null && request.content().length() > MAX_CONTENT_LENGTH) {
            throw new BadRequestException("content must not exceed " + MAX_CONTENT_LENGTH + " characters");
        }
        return new ValidatedRequest(name, request.content());
    }

    private static NotFoundException notFound(long id) {
        return new NotFoundException("infratest entry " + id + " not found");
    }

    private record ValidatedRequest(String name, String content) {
    }
}
