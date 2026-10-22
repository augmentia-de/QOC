package de.augmentia.oc.engine.runtime;

import de.augmentia.oc.engine.core.error.Defect;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.port.store.CredentialStore;
import de.augmentia.oc.engine.runtime.credential.Connection;
import de.augmentia.oc.engine.runtime.credential.CredentialService;
import de.augmentia.oc.engine.runtime.credential.Integration;
import de.augmentia.oc.engine.testing.InMemoryCredentials;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CredentialServiceTest {

    private static final Integration OPENAI = Integration.of("openai", "OPENAI_API_KEY");

    @Test
    void environmentIsTheDefaultWithoutStore() {
        CredentialService service = CredentialService.envOnly(name ->
                "OPENAI_API_KEY".equals(name) ? "sk-env" : null);

        assertThat(service.activeConnection(OPENAI).orElseThrow().kind()).isEqualTo(Connection.Kind.ENV);
        assertThat(service.secret(OPENAI).orElseThrow()).isEqualTo("sk-env");
    }

    @Test
    void missingEnvironmentYieldsNoConnection() {
        CredentialService service = CredentialService.envOnly(name -> null);
        assertThat(service.activeConnection(OPENAI).isEmpty()).isTrue();
        assertThat(service.secret(OPENAI).isEmpty()).isTrue();
    }

    @Test
    void storedBeatsEnvironment() {
        InMemoryCredentials store = new InMemoryCredentials();
        CredentialService service = new CredentialService(Option.some(store), name -> "sk-env");
        store.create(new CredentialStore.CredentialInput("openai", "key", "sk-stored"));

        assertThat(service.secret(OPENAI).orElseThrow()).isEqualTo("sk-stored");
    }

    @Test
    void inactiveStoredCredentialStillBlocksEnvironment() {
        InMemoryCredentials store = new InMemoryCredentials();
        CredentialService service = new CredentialService(Option.some(store), name -> "sk-env");
        var first = store.create(new CredentialStore.CredentialInput("openai", "key", "sk-first"));
        var second = store.create(new CredentialStore.CredentialInput("openai", "key", "sk-second"));
        store.activate(second.id());
        store.activate(first.id());
        store.activate(second.id());
        // Deactivate by activating a third? Keep it simple: remove the active one.
        store.remove(second.id());

        // The inactive first credential still exists, so the environment is not used.
        assertThat(service.connections(OPENAI)).hasSize(1);
        assertThat(service.connections(OPENAI).getFirst().kind()).isEqualTo(Connection.Kind.STORED);
    }

    @Test
    void activeStoredCredentialComesFirst() {
        InMemoryCredentials store = new InMemoryCredentials();
        CredentialService service = new CredentialService(Option.some(store), name -> "sk-env");
        var first = store.create(new CredentialStore.CredentialInput("openai", "key", "sk-first"));
        var second = store.create(new CredentialStore.CredentialInput("openai", "key", "sk-second"));
        store.activate(second.id());

        assertThat(service.activeConnection(OPENAI).orElseThrow().credential()
                .orElseThrow().id()).isEqualTo(second.id());
        assertThat(service.stored("openai")).hasSize(2);
    }

    @Test
    void differentIntegrationsAreSeparated() {
        InMemoryCredentials store = new InMemoryCredentials();
        store.create(new CredentialStore.CredentialInput("openai", "key", "sk-openai"));
        store.create(new CredentialStore.CredentialInput("anthropic", "key", "sk-anthropic"));

        assertThat(store.saved("openai")).extracting(credential -> credential.integration())
                .containsExactly("openai");
    }

    @Test
    void activatingOneCredentialDeactivatesTheOther() {
        InMemoryCredentials store = new InMemoryCredentials();
        var first = store.create(new CredentialStore.CredentialInput("openai", "key", "sk-first"));
        var second = store.create(new CredentialStore.CredentialInput("openai", "key", "sk-second"));

        store.activate(first.id());

        assertThat(store.get(first.id()).orElseThrow().active()).isTrue();
        assertThat(store.get(second.id()).orElseThrow().active()).isFalse();
        assertThat(store.saved("openai").stream().filter(credential -> credential.active())).hasSize(1);
    }

    @Test
    void missingStoreIsADefectForWrites() {
        CredentialService service = CredentialService.envOnly(name -> null);
        assertThatThrownBy(() -> service.create(new CredentialStore.CredentialInput("openai", "key", "x")))
                .isInstanceOf(Defect.class);
    }

    @Test
    void errorsNeverContainTheSecret() {
        CredentialService service = CredentialService.envOnly(name -> "super-secret");
        assertThat(service.toString()).doesNotContain("super-secret");
    }

    @Test
    void multipleEnvNamesAreConsideredInOrder() {
        Integration google = Integration.of("google", "GOOGLE_API_KEY", "GEMINI_API_KEY");
        Map<String, String> env = Map.of("GEMINI_API_KEY", "gemini");
        CredentialService service = CredentialService.envOnly(env::get);

        assertThat(service.activeConnection(google).orElseThrow().envName().orElseThrow())
                .isEqualTo("GEMINI_API_KEY");
        assertThat(service.secret(google).orElseThrow()).isEqualTo("gemini");
    }

    @Test
    void emptyIntegrationListHasNoConnections() {
        CredentialService service = CredentialService.envOnly(name -> "x");
        assertThat(service.connections(new Integration("local", List.of()))).isEmpty();
    }
}
