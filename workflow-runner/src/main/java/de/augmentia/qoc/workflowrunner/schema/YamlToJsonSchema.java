package de.augmentia.qoc.workflowrunner.schema;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts YAML content into a JSON Schema (as {@code Map}).
 *
 * <p>Two modes are supported (auto-detect):</p>
 * <ul>
 *   <li><b>OpenAPI:</b> If the YAML contains an {@code openapi} key, the
 *       document is treated as an OpenAPI 3.x specification and the named
 *       schema is extracted via {@link OpenApiToJsonSchema}.</li>
 *   <li><b>Plain JSON Schema:</b> Otherwise the parsed YAML map is returned
 *       as-is (it is expected to be a valid JSON Schema document in YAML
 *       form).</li>
 * </ul>
 */
public final class YamlToJsonSchema {

    private YamlToJsonSchema() {}

    /**
     * Parses YAML and returns either {@code {schema: ...}} or
     * {@code {schemas: [...]}} (OpenAPI without schema name).
     *
     * @param yaml       the YAML text
     * @param schemaName optional schema name for OpenAPI documents
     * @return a map with either "schema" or "schemas" key
     */
    public static Map<String, Object> fromYaml(String yaml, String schemaName) {
        if (yaml == null || yaml.isBlank()) {
            throw new IllegalArgumentException("yaml must not be blank");
        }
        ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
        Object obj;
        try {
            obj = yamlMapper.readValue(yaml, Object.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Ungültiges YAML: " + e.getMessage());
        }
        if (!(obj instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("YAML muss ein Objekt definieren");
        }
        Map<String, Object> m = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : map.entrySet()) {
            m.put(String.valueOf(e.getKey()), e.getValue());
        }

        if (m.containsKey("openapi")) {
            if (schemaName == null || schemaName.isBlank() || "null".equals(schemaName)) {
                List<String> names = OpenApiToJsonSchema.listSchemas(yaml);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("schemas", names);
                return result;
            }
            Map<String, Object> schema = OpenApiToJsonSchema.fromSpec(yaml, schemaName);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("schema", schema);
            return result;
        }

        // Plain JSON Schema in YAML form
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schema", m);
        return result;
    }
}
