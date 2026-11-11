package de.augmentia.oc.engine.runtime.resilience;

import de.augmentia.oc.engine.core.contract.provider.ProviderAdapter;
import de.augmentia.oc.engine.core.model.ModelRef;
import de.augmentia.oc.engine.core.model.ProviderId;
import de.augmentia.oc.engine.core.model.ModelId;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LlmTriageServiceTest {

    @Test
    void unavailableWithoutProvider() {
        LlmTriageService t = new LlmTriageService(null, null);
        TriageService.TriageResult r = t.analyze(new TriageService.TriageContext("tool", "err", "{}", "state", 1, 3));
        assertThat(r.retryable()).isFalse();
        assertThat(r.explanation()).contains("unavailable");
    }

    @Test
    void unavailableWithNullModel() {
        ProviderAdapter p = req -> java.util.stream.Stream.empty();
        LlmTriageService t = new LlmTriageService(p, null);
        TriageService.TriageResult r = t.analyze(new TriageService.TriageContext("tool", "err", "{}", "state", 1, 3));
        assertThat(r.retryable()).isFalse();
    }

    @Test
    void parsesRetryableJson() {
        ProviderAdapter p = req -> java.util.stream.Stream.of(
                new de.augmentia.oc.engine.core.contract.provider.ModelEvent.TextDelta("b", "{\"retryable\":true,\"correctedInput\":\"x\",\"explanation\":\"ok\"}"));
        de.augmentia.oc.engine.core.model.ModelRef model = de.augmentia.oc.engine.core.model.ModelRef.of(
                de.augmentia.oc.engine.core.model.ProviderId.create(),
                de.augmentia.oc.engine.core.model.ModelId.create());
        LlmTriageService t = new LlmTriageService(p, model);
        TriageService.TriageResult r = t.analyze(new TriageService.TriageContext("tool", "err", "{}", "state", 1, 3));
        assertThat(r.retryable()).isTrue();
        assertThat(r.correctedInput()).isEqualTo("x");
        assertThat(r.explanation()).isEqualTo("ok");
    }

    @Test
    void handlesMalformedResponse() {
        ProviderAdapter p = req -> java.util.stream.Stream.of(
                new de.augmentia.oc.engine.core.contract.provider.ModelEvent.TextDelta("b", "not json"));
        de.augmentia.oc.engine.core.model.ModelRef model = de.augmentia.oc.engine.core.model.ModelRef.of(
                de.augmentia.oc.engine.core.model.ProviderId.create(),
                de.augmentia.oc.engine.core.model.ModelId.create());
        LlmTriageService t = new LlmTriageService(p, model);
        TriageService.TriageResult r = t.analyze(new TriageService.TriageContext("tool", "err", "{}", "state", 1, 3));
        assertThat(r.retryable()).isFalse();
    }
}
