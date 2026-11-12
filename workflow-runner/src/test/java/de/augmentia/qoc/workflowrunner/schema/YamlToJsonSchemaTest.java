package de.augmentia.qoc.workflowrunner.schema;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class YamlToJsonSchemaTest {

    @Test
    void plainYaml() {
        String yaml = "type: object\nproperties:\n  name:\n    type: string\n  age:\n    type: integer\nrequired: [name]";
        Map<String, Object> result = YamlToJsonSchema.fromYaml(yaml, null);
        assertTrue(result.containsKey("schema"));
        Map<String, Object> schema = (Map<String, Object>) result.get("schema");
        assertEquals("object", schema.get("type"));
        assertTrue(((Map<?, ?>) schema.get("properties")).containsKey("name"));
    }

    @Test
    void openApiYaml() {
        String yaml = "openapi: 3.0.0\ninfo: { title: t, version: '1' }\npaths: {}\ncomponents:\n  schemas:\n    Foo:\n      type: object\n      properties:\n        id: { type: integer }\n      required: [id]";
        Map<String, Object> result = YamlToJsonSchema.fromYaml(yaml, "Foo");
        assertTrue(result.containsKey("schema"));
        Map<String, Object> schema = (Map<String, Object>) result.get("schema");
        assertEquals("object", schema.get("type"));
        assertTrue(((Map<?, ?>) schema.get("properties")).containsKey("id"));
    }

    @Test
    void openApiListSchemas() {
        String yaml = "openapi: 3.0.0\ninfo: { title: t, version: '1' }\npaths: {}\ncomponents:\n  schemas:\n    Foo:\n      type: object\n    Bar:\n      type: object";
        Map<String, Object> result = YamlToJsonSchema.fromYaml(yaml, null);
        assertTrue(result.containsKey("schemas"));
        List<String> names = (List<String>) result.get("schemas");
        assertTrue(names.contains("Foo"));
        assertTrue(names.contains("Bar"));
    }
}
