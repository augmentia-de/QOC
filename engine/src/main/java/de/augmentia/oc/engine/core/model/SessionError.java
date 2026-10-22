package de.augmentia.oc.engine.core.model;

import java.time.Duration;

/**
 * Channel 1: expected, classified failure. Errors are values, not the
 * {@link Aborted} or {@link Defect} channels.
 */
public sealed interface SessionError {

    String type();

    String message();

    record ProviderError(String type, String message, Integer status, String responseBody,
                         Duration retryAfter) implements SessionError {}

    record PermissionRejected(String type, String message) implements SessionError {}

    record ToolExecutionError(String type, String message) implements SessionError {}

    record CompactionError(String type, String message) implements SessionError {}

    record Unknown(String type, String message) implements SessionError {}
}