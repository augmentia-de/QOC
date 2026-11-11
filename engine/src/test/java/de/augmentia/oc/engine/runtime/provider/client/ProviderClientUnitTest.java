package de.augmentia.oc.engine.runtime.provider.client;

import de.augmentia.oc.engine.core.contract.provider.FailureKind;
import de.augmentia.oc.engine.core.contract.provider.InvalidRequestClassification;
import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.json.Schema;
import de.augmentia.oc.engine.core.model.Option;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderClientUnitTest {

    @Test
    void errorClassifierStatuses() {
        ProviderErrorClassifier c = new ProviderErrorClassifier();
        assertThat(c.classify(401, null, null, Option.none())).isInstanceOf(FailureKind.Authentication.class);
        assertThat(c.classify(403, null, null, Option.none())).isInstanceOf(FailureKind.Authentication.class);
        assertThat(c.classify(402, null, null, Option.none())).isInstanceOf(FailureKind.QuotaExceeded.class);
        assertThat(c.classify(429, null, null, Option.some(Duration.ofSeconds(5)))).isInstanceOf(FailureKind.RateLimited.class);
        assertThat(c.classify(400, null, null, Option.none())).isInstanceOf(FailureKind.InvalidRequest.class);
        assertThat(c.classify(404, null, null, Option.none())).isInstanceOf(FailureKind.NoRoute.class);
        assertThat(c.classify(408, null, null, Option.none())).isInstanceOf(FailureKind.Timeout.class);
        assertThat(c.classify(413, null, null, Option.none())).isInstanceOf(FailureKind.InvalidRequest.class);
        assertThat(c.classify(422, null, null, Option.none())).isInstanceOf(FailureKind.ContentPolicy.class);
        assertThat(c.classify(501, "code", null, Option.none())).isInstanceOf(FailureKind.UnsupportedOperation.class);
        assertThat(c.classify(500, null, null, Option.none())).isInstanceOf(FailureKind.ProviderInternal.class);
        assertThat(c.classify(418, null, null, Option.none())).isInstanceOf(FailureKind.InvalidRequest.class);
    }

    @Test
    void errorClassifierContextOverflow() {
        ProviderErrorClassifier c = new ProviderErrorClassifier();
        assertThat(c.isContextOverflow("context_length_exceeded", null)).isTrue();
        assertThat(c.isContextOverflow("other", null)).isFalse();
        assertThat(c.isContextOverflow(null, null)).isFalse();
        FailureKind.InvalidRequest r = (FailureKind.InvalidRequest) c.classify(400, "context_length_exceeded", "t", Option.none());
        assertThat(r.classification()).isEqualTo(InvalidRequestClassification.CONTEXT_OVERFLOW);
    }

    @Test
    void openAiSchemaBasic() {
        OpenAiSchema s = new OpenAiSchema();
        assertThat(s.encode(Schema.str())).isEqualTo(JsonValue.of(Map.of("type", JsonValue.of("string"))));
        assertThat(s.encode(new Schema.Int())).isNotNull();
    }

    @Test
    void openAiSchemaStrictObject() {
        Schema.Obj obj = new Schema.Obj(Map.of("name", Schema.str(), "age", Schema.opt(new Schema.Int())), true);
        JsonValue v = new OpenAiSchema().encode(obj, true);
        assertThat(v).isNotNull();
        assertThat(((JsonValue.Obj)v).values().get("required")).isNotNull();
        assertThat(((JsonValue.Obj)v).values().get("additionalProperties")).isEqualTo(JsonValue.of(false));
    }

    @Test
    void openAiSchemaNonStrictOptional() {
        Schema.Obj obj = new Schema.Obj(Map.of("name", Schema.str(), "age", Schema.opt(new Schema.Int())), true);
        JsonValue v = new OpenAiSchema().encode(obj, false);
        assertThat(v).isNotNull();
        assertThat(((JsonValue.Obj)v).values().get("additionalProperties")).isEqualTo(JsonValue.of(true));
    }

    @Test
    void openAiSchemaArrayAndEnum() {
        Schema.Arr arr = new Schema.Arr(Schema.str());
        JsonValue v = new OpenAiSchema().encode(arr);
        assertThat(((JsonValue.Obj)v).values().get("type")).isEqualTo(JsonValue.of("array"));
        Schema.Enum en = new Schema.Enum(List.of("a", "b"));
        JsonValue ev = new OpenAiSchema().encode(en);
        assertThat(((JsonValue.Obj)ev).values().get("enum")).isNotNull();
    }

    @Test
    void openAiSchemaUnionAndAny() {
        Schema.Union u = new Schema.Union(List.of(Schema.str(), new Schema.Int()));
        assertThat(((JsonValue.Obj)new OpenAiSchema().encode(u)).values().get("anyOf")).isNotNull();
        assertThat(new OpenAiSchema().encode(new Schema.Any())).isEqualTo(JsonValue.of(Map.of()));
    }
}
