package de.augmentia.oc.engine.runtime.tool.a2a;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class A2AEgressTest {

    @Test
    void blankUrlBlocked() {
        assertThat(A2AEgress.check(null, List.of(), List.of(), false)).isEqualTo("url is blank");
        assertThat(A2AEgress.check("  ", List.of(), List.of(), false)).isEqualTo("url is blank");
    }

    @Test
    void invalidUrlBlocked() {
        assertThat(A2AEgress.check("not a url", List.of(), List.of(), false)).startsWith("invalid url");
    }

    @Test
    void tlsRequiredBlocksHttp() {
        assertThat(A2AEgress.check("http://example.com/x", List.of(), List.of(), true))
                .isEqualTo("tls required but scheme is http");
    }

    @Test
    void tlsAllowedForHttps() {
        assertThat(A2AEgress.check("https://example.com/x", List.of(), List.of(), true)).isNull();
    }

    @Test
    void hostAllowlist() {
        assertThat(A2AEgress.check("https://api.example.com/x", List.of("example.com"), List.of(), false)).isNull();
        assertThat(A2AEgress.check("https://evil.com/x", List.of("example.com"), List.of(), false))
                .isEqualTo("host not allowed: evil.com");
    }

    @Test
    void emptyListsAllowAll() {
        assertThat(A2AEgress.check("http://anything.test/x", List.of(), List.of(), false)).isNull();
    }

    @Test
    void ipAllowlistExact() {
        // localhost resolves to 127.0.0.1
        assertThat(A2AEgress.check("http://localhost/x", List.of(), List.of("127.0.0.1"), false)).isNull();
        assertThat(A2AEgress.check("http://localhost/x", List.of(), List.of("10.0.0.1"), false))
                .startsWith("ip not allowed");
    }

    @Test
    void cidrAllowlist() {
        assertThat(A2AEgress.check("http://localhost/x", List.of(), List.of("127.0.0.0/8"), false)).isNull();
    }
}
