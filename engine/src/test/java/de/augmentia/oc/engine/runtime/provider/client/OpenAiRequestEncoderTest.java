package de.augmentia.oc.engine.runtime.provider.client;

import de.augmentia.oc.engine.core.contract.provider.GenerationOptions;
import de.augmentia.oc.engine.core.contract.provider.ModelContent;
import de.augmentia.oc.engine.core.contract.provider.ModelMessage;
import de.augmentia.oc.engine.core.contract.provider.ModelRequest;
import de.augmentia.oc.engine.core.json.Json;
import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.model.ModelId;
import de.augmentia.oc.engine.core.model.ModelRef;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.ProviderId;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenAiRequestEncoderTest {

    @Test
    void constructorRejectsBlankModelName() {
        assertThatThrownBy(() -> new OpenAiRequestEncoder("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OpenAiRequestEncoder(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void applyProducesJsonWithModelAndMessages() {
        OpenAiRequestEncoder enc = new OpenAiRequestEncoder("gpt-test");
        ModelRequest req = new ModelRequest(
                ModelRef.of(ProviderId.create(), ModelId.create()),
                List.of("system prompt"),
                List.of(new ModelMessage.User(List.of(new ModelContent.Text("hi")))),
                List.of(), Option.none(), GenerationOptions.defaults(), Map.of(), Map.of(), Option.none());
        String json = enc.apply(req);
        assertThat(json).contains("\"model\":\"gpt-test\"");
        assertThat(json).contains("\"stream\":true");
        assertThat(json).contains("\"role\":\"system\"");
        assertThat(json).contains("\"role\":\"user\"");
        assertThat(json).contains("hi");
    }

    @Test
    void applyIncludesGenerationOptions() {
        OpenAiRequestEncoder enc = new OpenAiRequestEncoder("gpt-test");
        GenerationOptions gen = new GenerationOptions(Option.some(0.5), Option.some(100), Option.some(0.9), List.of("stop"));
        ModelRequest req = new ModelRequest(
                ModelRef.of(ProviderId.create(), ModelId.create()),
                List.of(), List.of(), List.of(), Option.none(), gen, Map.of(), Map.of(), Option.none());
        String json = enc.apply(req);
        assertThat(json).contains("\"temperature\":0.5");
        assertThat(json).contains("\"max_tokens\":100");
        assertThat(json).contains("\"top_p\":0.9");
        assertThat(json).contains("\"stop\"");
    }

    @Test
    void applyPassesProviderOptions() {
        OpenAiRequestEncoder enc = new OpenAiRequestEncoder("gpt-test");
        ModelRequest req = new ModelRequest(
                ModelRef.of(ProviderId.create(), ModelId.create()),
                List.of(), List.of(), List.of(), Option.none(), GenerationOptions.defaults(),
                Map.of("foo", "bar"), Map.of(), Option.none());
        String json = enc.apply(req);
        assertThat(json).contains("\"foo\"");
    }
}
