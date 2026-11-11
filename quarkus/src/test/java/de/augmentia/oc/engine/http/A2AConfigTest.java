package de.augmentia.oc.engine.http;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class A2AConfigTest {

    @Test
    void identifiesSameOriginAsSelf() {
        assertThat(A2AConfig.isSelfEndpoint(
                "https://test.augmentia.de", "https://test.augmentia.de")).isTrue();
        assertThat(A2AConfig.isSelfEndpoint(
                "https://test.augmentia.de/", "https://test.augmentia.de")).isTrue();
        assertThat(A2AConfig.isSelfEndpoint(
                "https://TEST.augmentia.de/a2a", "https://test.augmentia.de")).isTrue();
        assertThat(A2AConfig.isSelfEndpoint(
                "https://test.augmentia.de", "https://test.augmentia.de:443")).isTrue();
    }

    @Test
    void keepsDifferentOrigins() {
        assertThat(A2AConfig.isSelfEndpoint(
                "https://other.example.com", "https://test.augmentia.de")).isFalse();
        assertThat(A2AConfig.isSelfEndpoint(
                "http://test.augmentia.de", "https://test.augmentia.de")).isFalse();
        assertThat(A2AConfig.isSelfEndpoint(
                "https://test.augmentia.de:8443", "https://test.augmentia.de")).isFalse();
    }

    @Test
    void handlesMissingOrInvalidValues() {
        assertThat(A2AConfig.isSelfEndpoint(null, "https://test.augmentia.de")).isFalse();
        assertThat(A2AConfig.isSelfEndpoint("https://test.augmentia.de", null)).isFalse();
        assertThat(A2AConfig.isSelfEndpoint(" ", "https://test.augmentia.de")).isFalse();
        assertThat(A2AConfig.isSelfEndpoint("not a url", "not a url")).isTrue();
        assertThat(A2AConfig.isSelfEndpoint("not a url", "other")).isFalse();
    }
}
