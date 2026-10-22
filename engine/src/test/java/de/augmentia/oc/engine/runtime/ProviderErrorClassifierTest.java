package de.augmentia.oc.engine.runtime;

import de.augmentia.oc.engine.core.contract.provider.FailureKind;
import de.augmentia.oc.engine.core.contract.provider.InvalidRequestClassification;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.runtime.provider.client.ProviderErrorClassifier;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderErrorClassifierTest {

    private final ProviderErrorClassifier classifier = new ProviderErrorClassifier();

    @Test
    void mapsAuthenticationAndQuota() {
        assertThat(classifier.classify(401, null, null, Option.none()))
                .isInstanceOf(FailureKind.Authentication.class);
        assertThat(classifier.classify(403, null, null, Option.none()))
                .isInstanceOf(FailureKind.Authentication.class);
        assertThat(classifier.classify(402, null, null, Option.none()))
                .isInstanceOf(FailureKind.QuotaExceeded.class);
    }

    @Test
    void mapsRateLimitWithRetryAfter() {
        FailureKind kind = classifier.classify(429, null, null, Option.some(Duration.ofSeconds(3)));
        assertThat(kind).isInstanceOf(FailureKind.RateLimited.class);
        assertThat(((FailureKind.RateLimited) kind).retryAfter().orElseThrow())
                .isEqualTo(Duration.ofSeconds(3));
    }

    @Test
    void contextOverflowIsReadFromTheStructuralCode() {
        FailureKind kind = classifier.classify(400, "context_length_exceeded",
                "invalid_request_error", Option.none());
        assertThat(kind).isInstanceOf(FailureKind.InvalidRequest.class);
        assertThat(((FailureKind.InvalidRequest) kind).classification())
                .isEqualTo(InvalidRequestClassification.CONTEXT_OVERFLOW);
    }

    @Test
    void other400IsNotContextOverflow() {
        FailureKind kind = classifier.classify(400, "invalid_schema", "invalid_request_error",
                Option.none());
        assertThat(((FailureKind.InvalidRequest) kind).classification())
                .isEqualTo(InvalidRequestClassification.OTHER);
    }

    @Test
    void messageTextIsNeverConsulted() {
        // "context_length_exceeded" only in free text is not enough.
        FailureKind kind = classifier.classify(400, "something", "other",
                Option.none());
        assertThat(((FailureKind.InvalidRequest) kind).classification())
                .isEqualTo(InvalidRequestClassification.OTHER);
    }

    @Test
    void mapsOtherStatuses() {
        assertThat(classifier.classify(404, null, null, Option.none()))
                .isInstanceOf(FailureKind.NoRoute.class);
        assertThat(classifier.classify(408, null, null, Option.none()))
                .isInstanceOf(FailureKind.Timeout.class);
        assertThat(classifier.classify(413, null, null, Option.none()))
                .isInstanceOf(FailureKind.InvalidRequest.class);
        assertThat(classifier.classify(422, null, null, Option.none()))
                .isInstanceOf(FailureKind.ContentPolicy.class);
        assertThat(classifier.classify(501, "not_supported", null, Option.none()))
                .isInstanceOf(FailureKind.UnsupportedOperation.class);
        assertThat(classifier.classify(500, null, null, Option.none()))
                .isInstanceOf(FailureKind.ProviderInternal.class);
        assertThat(classifier.classify(503, null, null, Option.none()))
                .isInstanceOf(FailureKind.ProviderInternal.class);
        assertThat(classifier.classify(418, null, null, Option.none()))
                .isInstanceOf(FailureKind.InvalidRequest.class);
    }

    @Test
    void isContextOverflowUsesTheCodeSet() {
        assertThat(classifier.isContextOverflow("context_window_exceeded", null)).isTrue();
        assertThat(classifier.isContextOverflow("request_too_large", null)).isTrue();
        assertThat(classifier.isContextOverflow("unknown", "invalid_request_error")).isFalse();
        assertThat(classifier.isContextOverflow(null, null)).isFalse();
    }
}
