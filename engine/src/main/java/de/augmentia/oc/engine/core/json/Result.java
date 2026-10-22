package de.augmentia.oc.engine.core.json;

/**
 * A simple success/failure result. Used by the JSON codec instead of exceptions
 * for expected decode failures.
 */
public sealed interface Result<T> {
    record Ok<T>(T value) implements Result<T> {}
    record Err<T>(DecodeError error) implements Result<T> {}

    default boolean isOk() {
        return this instanceof Ok<T>;
    }

    @SuppressWarnings("unchecked")
    default T valueOrNull() {
        return this instanceof Ok<T> o ? o.value() : null;
    }
}