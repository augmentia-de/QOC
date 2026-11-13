package de.augmentia.oc.engine.runtime.provider.client;

import de.augmentia.oc.engine.core.contract.provider.FailureKind;
import de.augmentia.oc.engine.core.contract.provider.InvalidOutputClassification;
import de.augmentia.oc.engine.core.contract.provider.InvalidRequestClassification;
import de.augmentia.oc.engine.core.contract.provider.ModelEvent;
import de.augmentia.oc.engine.core.contract.provider.OutcomePayload;
import de.augmentia.oc.engine.core.contract.provider.TransportDelivery;
import de.augmentia.oc.engine.core.contract.provider.TransportOperation;
import de.augmentia.oc.engine.core.model.CallId;
import de.augmentia.oc.engine.core.model.FinishReason;
import de.augmentia.oc.engine.core.model.ToolName;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Branch coverage for every normalized wire event and failure kind. */
class JsonEventTranslatorCoverageTest {

    private final JsonEventTranslator translator = new JsonEventTranslator();

    private List<ModelEvent> translate(String json) {
        return translator.translate(new SseReader.SseFrame("message", json));
    }

    private ModelEvent.ProviderFailed failed(String json) {
        List<ModelEvent> events = translate(json);
        assertThat(events).hasSize(1);
        assertThat(events.getFirst()).isInstanceOf(ModelEvent.ProviderFailed.class);
        return (ModelEvent.ProviderFailed) events.getFirst();
    }

    private FailureKind kind(String json) {
        return failed(json).kind();
    }

    @Test
    void translatesReasoningStartAndDelta() {
        assertThat(translate("{\"type\":\"reasoning.started\",\"blockId\":\"r\"}"))
                .containsExactly(new ModelEvent.ReasoningStarted("r"));
        assertThat(translate("{\"type\":\"reasoning.delta\",\"blockId\":\"r\",\"text\":\"why\"}"))
                .containsExactly(new ModelEvent.ReasoningDelta("r", "why"));
    }

    @Test
    void translatesToolInputEndedAndToolFailed() {
        String id = CallId.create().value();
        assertThat(translate("{\"type\":\"tool.input.ended\",\"callId\":\"" + id + "\",\"text\":\"{}\"}"))
                .containsExactly(new ModelEvent.ToolInputEnded(CallId.parse(id), "{}"));
        assertThat(translate("{\"type\":\"tool.failed\",\"callId\":\"" + id + "\",\"text\":\"boom\"}"))
                .containsExactly(new ModelEvent.ToolFailed(CallId.parse(id), "boom"));
    }

    @Test
    void toolResultCarriesProviderExecutedAndJsonOutcome() {
        String id = CallId.create().value();
        ModelEvent.ToolResultProvided event = (ModelEvent.ToolResultProvided) translate(
                "{\"type\":\"tool.result\",\"callId\":\"" + id + "\",\"providerExecuted\":true,"
                        + "\"outcome\":{\"type\":\"json\",\"value\":{\"a\":1}}}").getFirst();
        assertThat(event.callId()).isEqualTo(CallId.parse(id));
        assertThat(event.providerExecuted()).isTrue();
        assertThat(event.outcome()).isInstanceOf(OutcomePayload.Json.class);
    }

    @Test
    void toolResultSupportsContentTextAndFallbackOutcomes() {
        String id = CallId.create().value();
        ModelEvent.ToolResultProvided content = (ModelEvent.ToolResultProvided) translate(
                "{\"type\":\"tool.result\",\"callId\":\"" + id + "\",\"outcome\":{\"type\":\"content\"}}").getFirst();
        assertThat(content.outcome()).isInstanceOf(OutcomePayload.Content.class);
        assertThat(content.providerExecuted()).isFalse();

        ModelEvent.ToolResultProvided text = (ModelEvent.ToolResultProvided) translate(
                "{\"type\":\"tool.result\",\"callId\":\"" + id + "\",\"outcome\":{\"type\":\"text\",\"text\":\"ok\"}}")
                .getFirst();
        assertThat(((OutcomePayload.Text) text.outcome()).text()).isEqualTo("ok");

        ModelEvent.ToolResultProvided fallback = (ModelEvent.ToolResultProvided) translate(
                "{\"type\":\"tool.result\",\"callId\":\"" + id + "\",\"outcome\":5}").getFirst();
        assertThat(((OutcomePayload.Text) fallback.outcome()).text()).isEmpty();
    }

    @Test
    void mapsAbortedFinishAndUnknownReason() {
        assertThat(((ModelEvent.Finished) translate("{\"type\":\"finished\",\"reason\":\"aborted\"}").getFirst())
                .reason()).isEqualTo(FinishReason.ABORTED);
        assertThat(((ModelEvent.Finished) translate("{\"type\":\"finished\",\"reason\":\"weird\"}").getFirst())
                .reason()).isEqualTo(FinishReason.UNKNOWN);
        assertThat(((ModelEvent.Finished) translate("{\"type\":\"finished\"}").getFirst())
                .reason()).isEqualTo(FinishReason.UNKNOWN);
    }

    @Test
    void usageFallsBackToZeroAndClampsNegativeNumbers() {
        ModelEvent.Finished negative = (ModelEvent.Finished) translate(
                "{\"type\":\"finished\",\"reason\":\"normal\",\"usage\":{\"input\":-3,\"output\":-1}}").getFirst();
        assertThat(negative.usage().total()).isZero();

        ModelEvent.Finished nonObject = (ModelEvent.Finished) translate(
                "{\"type\":\"finished\",\"reason\":\"normal\",\"usage\":5}").getFirst();
        assertThat(nonObject.usage().total()).isZero();

        ModelEvent.Finished missing = (ModelEvent.Finished) translate(
                "{\"type\":\"finished\",\"reason\":\"normal\"}").getFirst();
        assertThat(missing.usage().total()).isZero();
    }

    @Test
    void mapsRateLimitedAndProviderInternalWithRetryAfter() {
        FailureKind.RateLimited rate = (FailureKind.RateLimited) kind(
                "{\"type\":\"provider.failed\",\"kind\":\"rate_limited\",\"retryAfter\":1500}");
        assertThat(rate.retryAfter().orElseThrow()).isEqualTo(Duration.ofMillis(1500));

        FailureKind.ProviderInternal internal = (FailureKind.ProviderInternal) kind(
                "{\"type\":\"provider.failed\",\"kind\":\"provider_internal\"}");
        assertThat(internal.retryAfter().isEmpty()).isTrue();
    }

    @Test
    void mapsTransportVariants() {
        FailureKind.Transport accepted = (FailureKind.Transport) kind(
                "{\"type\":\"provider.failed\",\"kind\":\"transport\",\"delivery\":\"accepted\","
                        + "\"operation\":\"read\",\"code\":\"ECONN\"}");
        assertThat(accepted.delivery()).isEqualTo(TransportDelivery.ACCEPTED);
        assertThat(accepted.operation()).isEqualTo(TransportOperation.READ);
        assertThat(accepted.code()).isEqualTo("ECONN");

        FailureKind.Transport rejected = (FailureKind.Transport) kind(
                "{\"type\":\"provider.failed\",\"kind\":\"transport\",\"delivery\":\"rejected\"}");
        assertThat(rejected.delivery()).isEqualTo(TransportDelivery.REJECTED);
        assertThat(rejected.operation()).isEqualTo(TransportOperation.SEND);
        assertThat(rejected.code()).isEmpty();

        FailureKind.Transport unknown = (FailureKind.Transport) kind(
                "{\"type\":\"provider.failed\",\"kind\":\"transport\",\"delivery\":\"else\",\"operation\":\"other\"}");
        assertThat(unknown.delivery()).isEqualTo(TransportDelivery.UNSENT);
        assertThat(unknown.operation()).isEqualTo(TransportOperation.SEND);
    }

    @Test
    void mapsInvalidOutputAndInvalidRequestClassifications() {
        FailureKind.InvalidOutput incomplete = (FailureKind.InvalidOutput) kind(
                "{\"type\":\"provider.failed\",\"kind\":\"invalid_output\",\"classification\":\"incomplete_stream\"}");
        assertThat(incomplete.classification()).isEqualTo(InvalidOutputClassification.INCOMPLETE_STREAM);

        FailureKind.InvalidOutput unknown = (FailureKind.InvalidOutput) kind(
                "{\"type\":\"provider.failed\",\"kind\":\"invalid_output\"}");
        assertThat(unknown.classification()).isEqualTo(InvalidOutputClassification.UNKNOWN);

        FailureKind.InvalidRequest overflow = (FailureKind.InvalidRequest) kind(
                "{\"type\":\"provider.failed\",\"kind\":\"invalid_request\",\"classification\":\"context_overflow\"}");
        assertThat(overflow.classification()).isEqualTo(InvalidRequestClassification.CONTEXT_OVERFLOW);

        FailureKind.InvalidRequest other = (FailureKind.InvalidRequest) kind(
                "{\"type\":\"provider.failed\",\"kind\":\"invalid_request\"}");
        assertThat(other.classification()).isEqualTo(InvalidRequestClassification.OTHER);
    }

    @Test
    void mapsAuthenticationQuotaAndContentPolicyHints() {
        FailureKind.Authentication auth = (FailureKind.Authentication) kind(
                "{\"type\":\"provider.failed\",\"kind\":\"authentication\",\"hint\":\"check key\"}");
        assertThat(auth.hint().orElseThrow()).isEqualTo("check key");
        assertThat(((FailureKind.Authentication) kind(
                "{\"type\":\"provider.failed\",\"kind\":\"authentication\"}")).hint().isEmpty()).isTrue();

        FailureKind.QuotaExceeded quota = (FailureKind.QuotaExceeded) kind(
                "{\"type\":\"provider.failed\",\"kind\":\"quota_exceeded\",\"hint\":\"wait\"}");
        assertThat(quota.hint().orElseThrow()).isEqualTo("wait");

        assertThat(kind("{\"type\":\"provider.failed\",\"kind\":\"content_policy\"}"))
                .isInstanceOf(FailureKind.ContentPolicy.class);
    }

    @Test
    void mapsRemainingFailureKinds() {
        FailureKind.UnsupportedOperation unsupported = (FailureKind.UnsupportedOperation) kind(
                "{\"type\":\"provider.failed\",\"kind\":\"unsupported_operation\",\"operation\":\"audio\"}");
        assertThat(unsupported.operation()).isEqualTo("audio");
        assertThat(((FailureKind.UnsupportedOperation) kind(
                "{\"type\":\"provider.failed\",\"kind\":\"unsupported_operation\"}")).operation()).isEmpty();

        assertThat(kind("{\"type\":\"provider.failed\",\"kind\":\"no_route\"}"))
                .isInstanceOf(FailureKind.NoRoute.class);
        assertThat(kind("{\"type\":\"provider.failed\",\"kind\":\"unknown_provider\"}"))
                .isInstanceOf(FailureKind.UnknownProvider.class);
        assertThat(kind("{\"type\":\"provider.failed\",\"kind\":\"timeout\"}"))
                .isInstanceOf(FailureKind.Timeout.class);
    }

    @Test
    void unknownOrMissingKindFallsBackToInvalidOutput() {
        assertThat(kind("{\"type\":\"provider.failed\",\"kind\":\"something_new\"}"))
                .isInstanceOf(FailureKind.InvalidOutput.class);
        assertThat(kind("{\"type\":\"provider.failed\"}"))
                .isInstanceOf(FailureKind.InvalidOutput.class);
    }

    @Test
    void missingTextFieldsBecomeEmptyStrings() {
        assertThat(translate("{\"type\":\"text.delta\",\"blockId\":\"b\"}"))
                .containsExactly(new ModelEvent.TextDelta("b", ""));
        assertThat(translate("{\"type\":\"text.started\"}"))
                .containsExactly(new ModelEvent.TextStarted(null));
    }

    @Test
    void missingCallIdAndNameAreReportedAsMalformed() {
        ModelEvent.ProviderFailed missingId = failed("{\"type\":\"tool.call\",\"name\":\"read\",\"input\":{}}");
        assertThat(missingId.message()).contains("missing callId");
        assertThat(missingId.kind()).isInstanceOf(FailureKind.InvalidOutput.class);

        ModelEvent.ProviderFailed missingName = failed(
                "{\"type\":\"tool.input.started\",\"callId\":\"" + CallId.create().value() + "\"}");
        assertThat(missingName.kind()).isInstanceOf(FailureKind.InvalidOutput.class);
    }

    @Test
    void nonObjectJsonHasNoType() {
        ModelEvent.ProviderFailed noType = failed("5");
        assertThat(noType.message()).contains("event without type");
    }

    @Test
    void toolInputStartedAndToolCallAreTranslated() {
        String id = CallId.create().value();
        assertThat(translate("{\"type\":\"tool.input.started\",\"callId\":\"" + id + "\",\"name\":\"read\"}"))
                .containsExactly(new ModelEvent.ToolInputStarted(CallId.parse(id), new ToolName("read")));
        assertThat(translate("{\"type\":\"tool.call\",\"callId\":\"" + id + "\",\"name\":\"read\",\"input\":{\"x\":1}}"))
                .containsExactly(new ModelEvent.ToolCallRequested(CallId.parse(id), new ToolName("read"),
                        de.augmentia.oc.engine.core.json.Json.parse("{\"x\":1}").valueOrNull()));
    }
}
