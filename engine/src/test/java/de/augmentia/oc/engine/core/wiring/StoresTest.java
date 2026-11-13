package de.augmentia.oc.engine.core.wiring;

import de.augmentia.oc.engine.adapter.memory.MemoryStores;
import de.augmentia.oc.engine.adapter.memory.RuntimeState;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StoresTest {

    private static Stores memoryStores() {
        return MemoryStores.create(new RuntimeState());
    }

    @Test
    void memoryStoresWiresAllPorts() {
        Stores stores = memoryStores();
        assertThat(stores.sessions()).isNotNull();
        assertThat(stores.sessionReader()).isSameAs(stores.sessions());
        assertThat(stores.conversation()).isNotNull();
        assertThat(stores.journal()).isNotNull();
        assertThat(stores.claims()).isNotNull();
        assertThat(stores.approvals()).isNotNull();
        assertThat(stores.instructions()).isNotNull();
        assertThat(stores.workspaces()).isNotNull();
        assertThat(stores.credentials()).isNotNull();
        assertThat(stores.snapshots()).isNotNull();
        assertThat(stores.toolOutputs()).isNotNull();
        assertThat(stores.toolCache()).isNotNull();
    }

    @Test
    void rejectsNullSessions() {
        Stores state = memoryStores();
        assertThatThrownBy(() -> new Stores(
                null, state.sessionReader(), state.conversation(), state.journal(), state.claims(),
                state.approvals(), state.instructions(), state.workspaces(), state.credentials(),
                state.snapshots(), state.toolOutputs(), state.toolCache()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNullConversation() {
        Stores state = memoryStores();
        assertThatThrownBy(() -> new Stores(
                state.sessions(), state.sessionReader(), null, state.journal(), state.claims(),
                state.approvals(), state.instructions(), state.workspaces(), state.credentials(),
                state.snapshots(), state.toolOutputs(), state.toolCache()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNullJournal() {
        Stores state = memoryStores();
        assertThatThrownBy(() -> new Stores(
                state.sessions(), state.sessionReader(), state.conversation(), null, state.claims(),
                state.approvals(), state.instructions(), state.workspaces(), state.credentials(),
                state.snapshots(), state.toolOutputs(), state.toolCache()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNullToolCache() {
        Stores state = memoryStores();
        assertThatThrownBy(() -> new Stores(
                state.sessions(), state.sessionReader(), state.conversation(), state.journal(), state.claims(),
                state.approvals(), state.instructions(), state.workspaces(), state.credentials(),
                state.snapshots(), state.toolOutputs(), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sessionReaderDefaultsToSessions() {
        Stores state = memoryStores();
        Stores stores = new Stores(
                state.sessions(), null, state.conversation(), state.journal(), state.claims(),
                state.approvals(), state.instructions(), state.workspaces(), state.credentials(),
                state.snapshots(), state.toolOutputs(), state.toolCache());
        assertThat(stores.sessionReader()).isSameAs(stores.sessions());
    }
}
