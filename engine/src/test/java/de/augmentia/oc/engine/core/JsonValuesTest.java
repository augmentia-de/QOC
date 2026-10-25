package de.augmentia.oc.engine.core;

import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.json.JsonValues;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JsonValuesTest {

    @Test
    void javaValuesRoundTrip() {
        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put("text", "hello");
        tree.put("number", 42);
        tree.put("decimal", new BigDecimal("1.5"));
        tree.put("flag", true);
        tree.put("missing", null);
        tree.put("list", List.of("a", 2));
        tree.put("nested", Map.of("key", "value"));

        JsonValue json = JsonValues.fromJava(tree);
        assertThat(json).isInstanceOf(JsonValue.Obj.class);

        Object back = JsonValues.toJava(json);
        assertThat(back).isInstanceOf(Map.class);
        Map<?, ?> result = (Map<?, ?>) back;
        assertThat(result.get("text")).isEqualTo("hello");
        assertThat(result.get("number")).isEqualTo(new BigDecimal("42"));
        assertThat(result.get("decimal")).isEqualTo(new BigDecimal("1.5"));
        assertThat(result.get("flag")).isEqualTo(true);
        assertThat(result.get("missing")).isNull();
        @SuppressWarnings("unchecked")
        List<Object> list = (List<Object>) result.get("list");
        assertThat(list).containsExactly("a", new BigDecimal("2"));
        @SuppressWarnings("unchecked")
        Map<String, Object> nested = (Map<String, Object>) result.get("nested");
        assertThat(nested).containsEntry("key", "value");
    }

    @Test
    void arraysAndJsonValuesPassThrough() {
        JsonValue value = JsonValue.of(List.of(JsonValue.of("x")));
        assertThat(JsonValues.fromJava(value)).isSameAs(value);
        assertThat(JsonValues.fromJava(new Object[]{"a", 1})).isInstanceOf(JsonValue.Arr.class);
        assertThat(JsonValues.toJava(null)).isNull();
    }

    @Test
    void unknownValuesBecomeStrings() {
        assertThat(JsonValues.fromJava(new Object())).isInstanceOf(JsonValue.Str.class);
        assertThat(JsonValues.toJava(JsonValue.ofNull())).isNull();
    }
}
