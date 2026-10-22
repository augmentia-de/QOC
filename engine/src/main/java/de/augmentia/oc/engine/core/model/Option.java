package de.augmentia.oc.engine.core.model;

import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Explicit optional value. The engine does not use {@link java.util.Optional}
 * as a field or parameter type (see Java-Impl §5.4).
 */
public sealed interface Option<T> {

    record Some<T>(T value) implements Option<T> {}
    record None<T>() implements Option<T> {}

    static <T> Option<T> of(T value) {
        return value == null ? new None<>() : new Some<>(value);
    }

    static <T> Option<T> some(T value) {
        return new Some<>(value);
    }

    static <T> Option<T> none() {
        return new None<>();
    }

    default boolean isPresent() {
        return this instanceof Some<T>;
    }

    default boolean isEmpty() {
        return this instanceof None<T>;
    }

    default T orElse(T fallback) {
        return this instanceof Some<T> s ? s.value() : fallback;
    }

    default <X extends Throwable> T orElseThrow(Supplier<? extends X> supplier) throws X {
        if (this instanceof Some<T> s) {
            return s.value();
        }
        throw supplier.get();
    }

    default T orElseThrow() {
        if (this instanceof Some<T> s) {
            return s.value();
        }
        throw new IllegalStateException("option is empty");
    }

    default <R> Option<R> map(Function<? super T, ? extends R> mapper) {
        return this instanceof Some<T> s ? Option.of(mapper.apply(s.value())) : Option.none();
    }
}