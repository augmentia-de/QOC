package de.augmentia.oc.engine.core;
import de.augmentia.oc.engine.core.contract.provider.FailureKind;
import de.augmentia.oc.engine.core.contract.provider.InvalidOutputClassification;
import de.augmentia.oc.engine.core.contract.provider.InvalidRequestClassification;
import de.augmentia.oc.engine.core.contract.provider.TransportDelivery;
import de.augmentia.oc.engine.core.contract.provider.TransportOperation;
import de.augmentia.oc.engine.core.eval.OverflowCompaction;
import de.augmentia.oc.engine.core.eval.ProviderClassifier;
import de.augmentia.oc.engine.core.model.Option;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProviderClassifierTest {

    @Test
    void retryableKinds() {
        assertThat(ProviderClassifier.isRetryable(new FailureKind.RateLimited(Option.none()))).isTrue();
        assertThat(ProviderClassifier.isRetryable(new FailureKind.ProviderInternal(Option.none()))).isTrue();
        assertThat(ProviderClassifier.isRetryable(new FailureKind.Timeout())).isTrue();
        assertThat(ProviderClassifier.isRetryable(new FailureKind.InvalidOutput(
                InvalidOutputClassification.INCOMPLETE_STREAM))).isTrue();
        assertThat(ProviderClassifier.isRetryable(new FailureKind.InvalidRequest(
                InvalidRequestClassification.CONTEXT_OVERFLOW))).isTrue();
    }

    @Test
    void nonRetryableKinds() {
        assertThat(ProviderClassifier.isRetryable(new FailureKind.InvalidOutput(
                InvalidOutputClassification.UNKNOWN))).isFalse();
        assertThat(ProviderClassifier.isRetryable(new FailureKind.InvalidRequest(
                InvalidRequestClassification.OTHER))).isFalse();
        assertThat(ProviderClassifier.isRetryable(new FailureKind.Authentication(Option.none()))).isFalse();
        assertThat(ProviderClassifier.isRetryable(new FailureKind.QuotaExceeded(Option.none()))).isFalse();
        assertThat(ProviderClassifier.isRetryable(new FailureKind.ContentPolicy())).isFalse();
        assertThat(ProviderClassifier.isRetryable(new FailureKind.UnsupportedOperation("x"))).isFalse();
        assertThat(ProviderClassifier.isRetryable(new FailureKind.NoRoute())).isFalse();
        assertThat(ProviderClassifier.isRetryable(new FailureKind.UnknownProvider())).isFalse();
        assertThat(ProviderClassifier.isRetryable(null)).isFalse();
    }

    @Test
    void transportMatrix() {
        assertThat(ProviderClassifier.isRetryable(transport(TransportDelivery.UNSENT, TransportOperation.SEND))).isTrue();
        assertThat(ProviderClassifier.isRetryable(transport(TransportDelivery.UNSENT, TransportOperation.READ))).isTrue();
        assertThat(ProviderClassifier.isRetryable(transport(TransportDelivery.ACCEPTED, TransportOperation.READ))).isTrue();
        assertThat(ProviderClassifier.isRetryable(transport(TransportDelivery.ACCEPTED, TransportOperation.SEND))).isFalse();
        assertThat(ProviderClassifier.isRetryable(transport(TransportDelivery.REJECTED, TransportOperation.SEND))).isFalse();
        assertThat(ProviderClassifier.isRetryable(transport(TransportDelivery.REJECTED, TransportOperation.READ))).isFalse();
        assertThat(ProviderClassifier.isRetryable((FailureKind.Transport) null)).isFalse();
    }

    @Test
    void contextOverflowTakesTheCompactionPath() {
        FailureKind overflow = new FailureKind.InvalidRequest(InvalidRequestClassification.CONTEXT_OVERFLOW);
        assertThat(ProviderClassifier.needsCompaction(overflow)).isTrue();
        assertThat(ProviderClassifier.needsCompaction(new FailureKind.InvalidRequest(
                InvalidRequestClassification.OTHER))).isFalse();
        assertThat(ProviderClassifier.nextDelay(overflow, 0, 4, Option.none(), Duration.ofSeconds(10)).isEmpty())
                .isTrue();
    }

    @Test
    void retryAfterTakesPrecedence() {
        FailureKind kind = new FailureKind.RateLimited(Option.some(Duration.ofSeconds(7)));
        Option<Duration> delay = ProviderClassifier.nextDelay(kind, 0, 4,
                Option.some(Duration.ofSeconds(7)), Duration.ofSeconds(10));
        assertThat(delay.orElseThrow()).isEqualTo(Duration.ofSeconds(7));
    }

    @Test
    void retryStopsAtAttemptLimit() {
        FailureKind kind = new FailureKind.Timeout();
        assertThat(ProviderClassifier.nextDelay(kind, 2, 4, Option.none(), Duration.ofSeconds(10)).isPresent()).isTrue();
        assertThat(ProviderClassifier.nextDelay(kind, 3, 4, Option.none(), Duration.ofSeconds(10)).isEmpty()).isTrue();
        assertThat(ProviderClassifier.nextDelay(kind, -1, 4, Option.none(), Duration.ofSeconds(10)).isEmpty()).isTrue();
    }

    @Test
    void retryStopsWhenBudgetIsTooSmall() {
        FailureKind kind = new FailureKind.ProviderInternal(Option.none());
        assertThat(ProviderClassifier.nextDelay(kind, 0, 4, Option.none(), Duration.ofMillis(500)).isEmpty()).isTrue();
        assertThat(ProviderClassifier.nextDelay(kind, 0, 4, Option.none(), Duration.ofSeconds(5)).isPresent()).isTrue();
        assertThat(ProviderClassifier.nextDelay(kind, 0, 4, Option.none(), Duration.ofSeconds(-1)).isEmpty()).isTrue();
    }

    @Test
    void nonRetryableKindHasNoDelay() {
        assertThat(ProviderClassifier.nextDelay(new FailureKind.Authentication(Option.none()), 0, 4,
                Option.none(), Duration.ofSeconds(10)).isEmpty()).isTrue();
    }

    @Test
    void backoffIsExponentialAndCapped() {
        assertThat(ProviderClassifier.backoff(0)).isEqualTo(Duration.ofSeconds(1));
        assertThat(ProviderClassifier.backoff(1)).isEqualTo(Duration.ofSeconds(2));
        assertThat(ProviderClassifier.backoff(2)).isEqualTo(Duration.ofSeconds(4));
        assertThat(ProviderClassifier.backoff(3)).isEqualTo(Duration.ofSeconds(8));
        assertThat(ProviderClassifier.backoff(4)).isEqualTo(Duration.ofSeconds(16));
        assertThat(ProviderClassifier.backoff(5)).isEqualTo(Duration.ofSeconds(30));
        assertThat(ProviderClassifier.backoff(100)).isEqualTo(Duration.ofSeconds(30));
        assertThatThrownBy(() -> ProviderClassifier.backoff(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void jitterStaysWithinOneSecond() {
        Duration base = ProviderClassifier.backoff(0);
        assertThat(ProviderClassifier.backoff(0, 0)).isEqualTo(base);
        assertThat(ProviderClassifier.backoff(0, 0.999)).isBetween(base, base.plusSeconds(1));
        assertThatThrownBy(() -> ProviderClassifier.backoff(0, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProviderClassifier.backoff(0, -0.1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void overflowCompactionHappensAtMostOnce() {
        OverflowCompaction compaction = new OverflowCompaction();
        assertThat(compaction.used()).isFalse();
        assertThat(compaction.consume()).isTrue();
        assertThat(compaction.used()).isTrue();
        assertThat(compaction.consume()).isFalse();
        assertThat(compaction.consume()).isFalse();
    }

    private static FailureKind.Transport transport(TransportDelivery delivery, TransportOperation operation) {
        return new FailureKind.Transport(delivery, operation, "code");
    }
}
