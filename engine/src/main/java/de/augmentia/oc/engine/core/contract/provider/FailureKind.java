package de.augmentia.oc.engine.core.contract.provider;

import de.augmentia.oc.engine.core.model.*;

import java.time.Duration;

public sealed interface FailureKind extends ProviderFailure {

    record RateLimited(Option<Duration> retryAfter) implements FailureKind {
        public RateLimited {
            if (retryAfter == null) retryAfter = Option.none();
        }
    }

    record ProviderInternal(Option<Duration> retryAfter) implements FailureKind {
        public ProviderInternal {
            if (retryAfter == null) retryAfter = Option.none();
        }
    }

    record Transport(TransportDelivery delivery, TransportOperation operation, String code) implements FailureKind {
        public Transport {
            if (delivery == null || operation == null) throw new IllegalArgumentException("transport fields must not be null");
            if (code == null) code = "";
        }
    }

    record InvalidOutput(InvalidOutputClassification classification) implements FailureKind {
        public InvalidOutput {
            if (classification == null) classification = InvalidOutputClassification.UNKNOWN;
        }
    }

    record InvalidRequest(InvalidRequestClassification classification) implements FailureKind {
        public InvalidRequest {
            if (classification == null) classification = InvalidRequestClassification.OTHER;
        }
    }

    record Authentication(Option<String> hint) implements FailureKind {
        public Authentication {
            if (hint == null) hint = Option.none();
        }
    }

    record QuotaExceeded(Option<String> hint) implements FailureKind {
        public QuotaExceeded {
            if (hint == null) hint = Option.none();
        }
    }

    record ContentPolicy() implements FailureKind {}

    record UnsupportedOperation(String operation) implements FailureKind {
        public UnsupportedOperation {
            if (operation == null) operation = "";
        }
    }

    record NoRoute() implements FailureKind {}

    record UnknownProvider() implements FailureKind {}

    record Timeout() implements FailureKind {}
}