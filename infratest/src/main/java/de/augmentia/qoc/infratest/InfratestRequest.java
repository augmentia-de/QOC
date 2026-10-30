package de.augmentia.qoc.infratest;

/**
 * Request body for creating or updating an {@link InfratestEntry}.
 *
 * <p>The identifier is never part of the request: it is either generated on
 * creation or taken from the request path on update.</p>
 */
public record InfratestRequest(String name, String content) {
}
