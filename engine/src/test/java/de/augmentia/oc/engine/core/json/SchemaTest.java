package de.augmentia.oc.engine.core.json;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaTest {

    @Test
    void factoryMethodsCreateCorrectTypes() {
        assertThat(Schema.str()).isInstanceOf(Schema.Str.class);
        assertThat(Schema.intg()).isInstanceOf(Schema.Int.class);
        assertThat(Schema.bool()).isInstanceOf(Schema.Bool.class);
        assertThat(Schema.num()).isInstanceOf(Schema.Num.class);
        assertThat(Schema.any()).isInstanceOf(Schema.Any.class);
    }

    @Test
    void arrWrapsItem() {
        Schema arr = Schema.arr(Schema.str());
        assertThat(arr).isInstanceOf(Schema.Arr.class);
        assertThat(((Schema.Arr) arr).item()).isInstanceOf(Schema.Str.class);
    }

    @Test
    void objCreatesImmutableCopy() {
        Map<String, Schema> fields = new java.util.HashMap<>();
        fields.put("name", Schema.str());
        Schema obj = Schema.obj(fields);
        fields.put("extra", Schema.num());
        assertThat(((Schema.Obj) obj).fields()).doesNotContainKey("extra");
    }

    @Test
    void objWithAdditionalProperties() {
        Schema obj = Schema.obj(Map.of("x", Schema.str()), true);
        assertThat(((Schema.Obj) obj).additionalProperties()).isTrue();
    }

    @Test
    void optWrapsInner() {
        Schema opt = Schema.opt(Schema.str());
        assertThat(opt).isInstanceOf(Schema.Opt.class);
        assertThat(((Schema.Opt) opt).inner()).isInstanceOf(Schema.Str.class);
    }

    @Test
    void enmCopiesValues() {
        List<String> vals = new java.util.ArrayList<>(List.of("a", "b"));
        Schema enm = Schema.enm(vals);
        vals.add("c");
        assertThat(((Schema.Enum) enm).values()).containsExactly("a", "b");
    }

    @Test
    void unionCopiesOptions() {
        List<Schema> opts = new java.util.ArrayList<>(List.of(Schema.str(), Schema.intg()));
        Schema union = Schema.union(opts);
        opts.add(Schema.bool());
        assertThat(((Schema.Union) union).options()).hasSize(2);
    }

    @Test
    void equalityByValue() {
        assertThat(Schema.str()).isEqualTo(Schema.str());
        assertThat(Schema.arr(Schema.str())).isEqualTo(Schema.arr(Schema.str()));
    }
}
