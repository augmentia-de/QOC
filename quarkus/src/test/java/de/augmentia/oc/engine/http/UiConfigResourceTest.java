package de.augmentia.oc.engine.http;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class UiConfigResourceTest {

    private static final String[] PROPERTIES = {
            "engine.ui.oidc.enabled",
            "engine.ui.oidc.issuer",
            "engine.ui.oidc.client-id",
            "engine.ui.oidc.audience",
            "engine.ui.oidc.api-scope"
    };

    @AfterEach
    void clearProperties() {
        for (String property : PROPERTIES) {
            System.clearProperty(property);
        }
    }

    @Test
    void defaultsToOidcFreeMode() {
        assumeNoEnvironmentOverrides();
        String js = new UiConfigResource().config();
        assertThat(js).contains("\"oidcEnabled\":false");
        assertThat(js).contains("\"clientId\":\"qoc-ui\"");
        assertThat(js).doesNotContain("\"issuer\"");
    }

    @Test
    void rendersConfiguredOidcValues() {
        assumeNoEnvironmentOverrides();
        System.setProperty("engine.ui.oidc.enabled", "true");
        System.setProperty("engine.ui.oidc.issuer", "http://localhost:8180/realms/qoc");
        System.setProperty("engine.ui.oidc.client-id", "qoc-ui");
        System.setProperty("engine.ui.oidc.audience", "qoc-engine");
        System.setProperty("engine.ui.oidc.api-scope", "qoc:use");

        String js = new UiConfigResource().config();

        assertThat(js).contains("\"oidcEnabled\":true");
        assertThat(js).contains("\"issuer\":\"http://localhost:8180/realms/qoc\"");
        assertThat(js).contains("\"clientId\":\"qoc-ui\"");
        assertThat(js).contains("\"audience\":\"qoc-engine\"");
        assertThat(js).contains("\"apiScope\":\"qoc:use\"");
    }

    @Test
    void escapesQuotesInValues() {
        assumeNoEnvironmentOverrides();
        System.setProperty("engine.ui.oidc.enabled", "true");
        System.setProperty("engine.ui.oidc.issuer", "http://example.test/\"quoted\"");
        String js = new UiConfigResource().config();
        assertThat(js).contains("\\\"quoted\\\"");
    }

    private static void assumeNoEnvironmentOverrides() {
        assumeTrue(System.getenv("ENGINE_UI_OIDC_ENABLED") == null);
        assumeTrue(System.getenv("ENGINE_UI_OIDC_ISSUER") == null);
        assumeTrue(System.getenv("ENGINE_UI_OIDC_CLIENT_ID") == null);
        assumeTrue(System.getenv("ENGINE_UI_OIDC_AUDIENCE") == null);
        assumeTrue(System.getenv("ENGINE_UI_OIDC_API_SCOPE") == null);
    }
}
