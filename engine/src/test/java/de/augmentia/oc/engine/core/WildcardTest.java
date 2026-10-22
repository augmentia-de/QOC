package de.augmentia.oc.engine.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WildcardTest {

    @Test
    void trailingSpaceStarMatchesWithoutArguments() {
        // A-03
        assertThat(Wildcard.match("git status", "git status *")).isTrue();
        assertThat(Wildcard.match("git status -sb", "git status *")).isTrue();
    }

    @Test
    void starDoesNotCrossIntendedBoundary() {
        // A-04
        assertThat(Wildcard.match("x.envy", "*.env")).isFalse();
        assertThat(Wildcard.match(".env", "*.env")).isTrue();
        assertThat(Wildcard.match("prod.env", "*.env")).isTrue();
        assertThat(Wildcard.match("a/b/.env", "*.env")).isTrue();
    }

    @Test
    void questionMarkMatchesSingleChar() {
        assertThat(Wildcard.match("a.txt", "?.txt")).isTrue();
        assertThat(Wildcard.match("ab.txt", "?.txt")).isFalse();
    }

    @Test
    void backslashNormalizesToSlash() {
        assertThat(Wildcard.match("a\\b.txt", "a/b.txt")).isTrue();
    }

    @Test
    void anchoringPreventsPartial() {
        assertThat(Wildcard.match("xx.env", "*.env")).isTrue();
        assertThat(Wildcard.match("prefixx.envy", "*.env")).isFalse();
    }
}