package de.augmentia.qoc.workflowrunner.schema;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

public class JavaRecordToJsonSchemaTest {

    public record Person(String name, int age, Optional<String> email, List<String> tags, Address address) {}
    public record Address(String city, String zip) {}

    @Test
    void convertsRecord() {
        Map<String, Object> schema = JavaRecordToJsonSchema.fromClass(Person.class);
        System.out.println(schema);
        assertEquals("object", schema.get("type"));
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        assertTrue(props.containsKey("name"));
        assertEquals("string", ((Map<?,?>) props.get("name")).get("type"));
        assertEquals("integer", ((Map<?,?>) props.get("age")).get("type"));
        // optional email -> not in required
        List<String> required = (List<String>) schema.get("required");
        assertFalse(required.contains("email"));
        assertTrue(required.contains("name"));
        assertTrue(required.contains("age"));
    }

    @Test
    void convertsOpenApi() {
        String spec = """
            openapi: 3.0.0
            info: { title: t, version: '1' }
            paths: {}
            components:
              schemas:
                Foo:
                  type: object
                  properties:
                    id: { type: integer }
                    name: { type: string }
                  required: [id]
            """;
        List<String> names = OpenApiToJsonSchema.listSchemas(spec);
        assertTrue(names.contains("Foo"));
        Map<String, Object> s = OpenApiToJsonSchema.fromSpec(spec, "Foo");
        assertEquals("object", s.get("type"));
        Map<String, Object> props = (Map<String, Object>) s.get("properties");
        assertTrue(props.containsKey("id"));
        assertTrue(props.containsKey("name"));
    }
}
