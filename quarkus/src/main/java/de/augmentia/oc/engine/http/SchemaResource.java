package de.augmentia.oc.engine.http;

import com.fasterxml.jackson.databind.ObjectMapper;

import de.augmentia.oc.engine.core.json.JsonSchema;
import de.augmentia.oc.engine.core.json.JsonValues;
import de.augmentia.oc.engine.core.json.Result;
import de.augmentia.oc.engine.core.json.Schema;
import de.augmentia.qoc.workflowrunner.schema.JavaRecordToJsonSchema;
import de.augmentia.qoc.workflowrunner.schema.OpenApiToJsonSchema;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Path("/api/schema")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SchemaResource {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @POST
    @Path("/from-class")
    public Response fromClass(Map<String, Object> request) {
        String className = request == null ? null : String.valueOf(request.get("className"));
        if (className == null || className.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", "className required")).build();
        }
        try {
            Class<?> clazz = Class.forName(className.trim());
            Map<String, Object> schema = JavaRecordToJsonSchema.fromClass(clazz);
            return Response.ok(Map.of("schema", schema)).build();
        } catch (ClassNotFoundException e) {
            return Response.status(Response.Status.NOT_FOUND).entity(Map.of("error", "class not found: " + className)).build();
        } catch (Exception e) {
            return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", e.getMessage())).build();
        }
    }

    @POST
    @Path("/from-openapi")
    public Response fromOpenApi(Map<String, Object> request) {
        String spec = request == null ? null : String.valueOf(request.get("spec"));
        String name = request == null ? null : String.valueOf(request.get("schemaName"));
        if (spec == null || spec.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", "spec required")).build();
        }
        try {
            if (name == null || name.isBlank() || "null".equals(name)) {
                List<String> names = OpenApiToJsonSchema.listSchemas(spec);
                return Response.ok(Map.of("schemas", names)).build();
            }
            Map<String, Object> schema = OpenApiToJsonSchema.fromSpec(spec, name);
            return Response.ok(Map.of("schema", schema)).build();
        } catch (Exception e) {
            return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", e.getMessage())).build();
        }
    }

    @POST
    @Path("/validate")
    public Response validate(Map<String, Object> request) {
        Object schemaObj = request == null ? null : request.get("schema");
        if (schemaObj == null) {
            return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", "schema required")).build();
        }
        try {
            Result<Schema> parsed = JsonSchema.parse(JsonValues.fromJava(schemaObj));
            if (parsed instanceof Result.Err<Schema> err) {
                return Response.ok(Map.of("valid", false, "path", err.error().path(), "message", err.error().message())).build();
            }
            return Response.ok(Map.of("valid", true)).build();
        } catch (Exception e) {
            return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("valid", false, "error", e.getMessage())).build();
        }
    }
}
