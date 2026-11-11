package de.augmentia.qoc.workflowrunner.schema;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.ComposedSchema;
import io.swagger.v3.oas.models.media.MapSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Extracts a JSON Schema (as Map) from an OpenAPI 3.x document.
 */
public final class OpenApiToJsonSchema {

    private OpenApiToJsonSchema() {}

    /** Returns the names of all schemas defined in components. */
    public static List<String> listSchemas(String spec) {
        OpenAPI api = parse(spec);
        if (api == null || api.getComponents() == null || api.getComponents().getSchemas() == null) {
            return List.of();
        }
        return new ArrayList<>(api.getComponents().getSchemas().keySet());
    }

    /** Converts the named schema to a JSON Schema Map. */
    public static Map<String, Object> fromSpec(String spec, String schemaName) {
        OpenAPI api = parse(spec);
        if (api == null || api.getComponents() == null || api.getComponents().getSchemas() == null) {
            throw new IllegalArgumentException("no components/schemas found");
        }
        Schema s = api.getComponents().getSchemas().get(schemaName);
        if (s == null) throw new IllegalArgumentException("schema not found: " + schemaName);
        return convert(s, api, new ArrayList<>());
    }

    private static OpenAPI parse(String spec) {
        ParseOptions opts = new ParseOptions();
        opts.setResolve(true);
        return new OpenAPIV3Parser().readContents(spec, null, opts).getOpenAPI();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> convert(Schema s, OpenAPI api, List<String> seen) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (s == null) { out.put("type", "string"); return out; }
        // $ref
        if (s.get$ref() != null) {
            String ref = s.get$ref();
            String name = ref.substring(ref.lastIndexOf('/') + 1);
            if (seen.contains(name)) { out.put("type", "object"); return out; } // cycle guard
            seen = new ArrayList<>(seen); seen.add(name);
            if (api.getComponents().getSchemas() != null && api.getComponents().getSchemas().containsKey(name)) {
                return convert(api.getComponents().getSchemas().get(name), api, seen);
            }
        }
        String type = s.getType();
        if (type == null) {
            // composed
            if (s instanceof ComposedSchema cs) {
                if (cs.getAllOf() != null && !cs.getAllOf().isEmpty()) {
                    // merge properties
                    Map<String, Object> merged = new LinkedHashMap<>();
                    merged.put("type", "object");
                    Map<String, Object> props = new LinkedHashMap<>();
                    List<String> req = new ArrayList<>();
                    for (Schema part : cs.getAllOf()) {
                        Map<String, Object> p = convert(part, api, seen);
                        if (p.get("properties") instanceof Map<?,?> pm) for (Object k : pm.keySet()) props.put(String.valueOf(k), pm.get(k));
                        if (p.get("required") instanceof List<?> r) for (Object x : r) req.add(String.valueOf(x));
                    }
                    merged.put("properties", props);
                    if (!req.isEmpty()) merged.put("required", req);
                    merged.put("additionalProperties", false);
                    return merged;
                }
                if (cs.getAnyOf() != null && !cs.getAnyOf().isEmpty()) {
                    List<Map<String, Object>> opts = new ArrayList<>();
                    for (Schema part : cs.getAnyOf()) opts.add(convert(part, api, seen));
                    out.put("oneOf", opts); // treat anyOf as oneOf
                    return out;
                }
                if (cs.getOneOf() != null && !cs.getOneOf().isEmpty()) {
                    List<Map<String, Object>> opts = new ArrayList<>();
                    for (Schema part : cs.getOneOf()) opts.add(convert(part, api, seen));
                    out.put("oneOf", opts);
                    return out;
                }
            }
            out.put("type", "object");
            return out;
        }
        switch (type) {
            case "string" -> {
                out.put("type", "string");
                if (s.getFormat() != null) out.put("format", s.getFormat());
                if (s.getEnum() != null && !s.getEnum().isEmpty()) {
                    List<String> e = new ArrayList<>();
                    for (Object o : s.getEnum()) e.add(String.valueOf(o));
                    out.put("enum", e);
                }
            }
            case "integer" -> out.put("type", "integer");
            case "number" -> out.put("type", "number");
            case "boolean" -> out.put("type", "boolean");
            case "array" -> {
                out.put("type", "array");
                Schema items = s.getItems();
                out.put("items", items == null ? Map.of("type", "string") : convert(items, api, seen));
            }
            case "object" -> {
                out.put("type", "object");
                Map<String, Schema> props = s.getProperties();
                if (props != null && !props.isEmpty()) {
                    Map<String, Object> pmap = new LinkedHashMap<>();
                    for (Map.Entry<String, Schema> e : props.entrySet()) {
                        pmap.put(e.getKey(), convert(e.getValue(), api, seen));
                    }
                    out.put("properties", pmap);
                }
                if (s.getRequired() != null && !s.getRequired().isEmpty()) {
                    out.put("required", new ArrayList<>(s.getRequired()));
                }
                Object addl = s.getAdditionalProperties();
                if (addl instanceof Boolean b) out.put("additionalProperties", b);
                else if (addl instanceof Schema sc) out.put("additionalProperties", convert(sc, api, seen));
                else out.put("additionalProperties", false);
            }
            default -> out.put("type", "string");
        }
        // nullable (OpenAPI 3.0)
        if (Boolean.TRUE.equals(s.getNullable())) {
            // convert to type array? JsonSchema supports type arrays via our parser? Our parser supports type arrays.
            Object t = out.get("type");
            if (t instanceof String ts) out.put("type", List.of(ts, "null"));
        }
        return out;
    }
}
