package de.augmentia.qoc.workflowrunner.schema;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class JavaSourceToJsonSchemaTest {

    @Test
    void convertsRecord() {
        String src = "public record Person(String name, int age, java.util.Optional<String> email, java.util.List<String> tags) {}";
        Map<String, Object> schema = JavaSourceToJsonSchema.fromSource(src);
        assertEquals("object", schema.get("type"));
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        assertTrue(props.containsKey("name"));
        assertEquals("string", ((Map<?, ?>) props.get("name")).get("type"));
        assertEquals("integer", ((Map<?, ?>) props.get("age")).get("type"));
        assertEquals("array", ((Map<?, ?>) props.get("tags")).get("type"));
        List<String> required = (List<String>) schema.get("required");
        assertTrue(required.contains("name"));
        assertTrue(required.contains("age"));
        assertTrue(required.contains("tags"));
        assertFalse(required.contains("email"));
    }

    @Test
    void convertsPojo() {
        String src = "public class Foo {\n  public String name;\n  private int count;\n  public java.util.Optional<String> desc;\n}";
        Map<String, Object> schema = JavaSourceToJsonSchema.fromSource(src);
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        assertTrue(props.containsKey("name"));
        assertTrue(props.containsKey("count"));
        assertTrue(props.containsKey("desc"));
    }

    @Test
    void convertsNestedRecord() {
        String src = "public record Person(String name, Address address) {}\npublic record Address(String city, String zip) {}";
        Map<String, Object> schema = JavaSourceToJsonSchema.fromSource(src);
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        assertTrue(props.containsKey("address"));
        Map<String, Object> addr = (Map<String, Object>) props.get("address");
        assertEquals("object", addr.get("type"));
        assertTrue(((Map<?, ?>) addr.get("properties")).containsKey("city"));
    }

    @Test
    void convertsEnum() {
        String src = "public enum Color { RED, GREEN, BLUE }";
        Map<String, Object> schema = JavaSourceToJsonSchema.fromSource(src);
        assertEquals("string", schema.get("type"));
        assertTrue(((List<?>) schema.get("enum")).contains("RED"));
    }

    @Test
    void extractClassName() {
        assertEquals("Foo", JavaSourceToJsonSchema.extractClassName("public record Foo(int x) {}"));
    }

    @Test
    void invalidSourceThrows() {
        assertThrows(IllegalArgumentException.class, () -> JavaSourceToJsonSchema.fromSource("this is not java"));
    }
}
