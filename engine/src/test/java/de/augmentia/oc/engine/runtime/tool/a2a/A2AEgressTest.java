package de.augmentia.oc.engine.runtime.tool.a2a;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class A2AEgressTest {
    @Test
    void allowsWhenNoRestrictions() {
        assertThat(A2AEgress.check("http://example.com/x", List.of(), List.of(), false)).isNull();
    }

    @Test
    void blocksDisallowedHost() {
        assertThat(A2AEgress.check("http://evil.com/x", List.of("example.com"), List.of(), false))
                .contains("not allowed");
    }

    @Test
    void allowsSubdomainOfAllowedHost() {
        assertThat(A2AEgress.check("http://api.example.com/x", List.of("example.com"), List.of(), false)).isNull();
    }

    @Test
    void blocksTlsViolation() {
        assertThat(A2AEgress.check("http://example.com/x", List.of(), List.of(), true))
                .contains("tls required");
    }

    @Test
    void allowsHttpsWhenTlsRequired() {
        assertThat(A2AEgress.check("https://example.com/x", List.of(), List.of(), true)).isNull();
    }

    @Test
    void blocksDisallowedIp() {
        assertThat(A2AEgress.check("http://example.com/x", List.of(), List.of("10.0.0.1"), false))
                .isNotNull();
    }

    @Test
    void blocksBlankUrl() {
        assertThat(A2AEgress.check("  ", List.of(), List.of(), false)).isNotNull();
    }
}
