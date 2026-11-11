package de.augmentia.qoc.workflowrunner.schema;

import de.augmentia.oc.engine.core.json.JsonSchema;
import de.augmentia.oc.engine.core.json.JsonValues;
import de.augmentia.oc.engine.core.json.Result;
import de.augmentia.oc.engine.core.json.Schema;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class SchemaParseCompatTest {
    public record Foo(String name, int count) {}

    @Test
    void generatedSchemaParses() {
        Map<String, Object> js = JavaRecordToJsonSchema.fromClass(Foo.class);
        Result<Schema> r = JsonSchema.parse(JsonValues.fromJava(js));
        assertTrue(r instanceof Result.Ok, r.toString());
    }
}
