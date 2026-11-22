package de.augmentia.oc.engine.extension;

import de.augmentia.oc.engine.adapter.memory.InMemoryMemoryStore;
import de.augmentia.oc.engine.adapter.memory.InMemoryProvenanceStore;
import de.augmentia.oc.engine.core.model.Fact;
import de.augmentia.oc.engine.core.model.MessageId;
import de.augmentia.oc.engine.core.model.ProvenanceRecord;
import de.augmentia.oc.engine.core.model.SessionId;
import de.augmentia.oc.engine.core.wiring.ExtensionPorts;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExtensionPortsTest {

    @Test
    void defaultsAreNoOp() {
        ExtensionPorts ports = ExtensionPorts.defaults();
        assertThat(ports.provenance()).isNotNull();
        assertThat(ports.memory()).isNotNull();
        assertThat(ports.decision()).isNotNull();
        assertThat(ports.dataPolicy()).isNotNull();
        // no-op behaviour: no exceptions, empty results
        ports.provenance().append(SessionId.create(), new ProvenanceRecord(
                de.augmentia.oc.engine.core.model.MessageId.create(), List.of(), List.of(), List.of(),
                de.augmentia.oc.engine.core.model.Option.none(), "", Instant.now()));
        assertThat(ports.provenance().bySession(SessionId.create(), 10)).isEmpty();
    }

    @Test
    void inMemoryProvenanceStoreRoundTrip() {
        InMemoryProvenanceStore store = new InMemoryProvenanceStore();
        SessionId sid = SessionId.create();
        MessageId mid = MessageId.create();
        ProvenanceRecord rec = new ProvenanceRecord(mid, List.of(), List.of(), List.of(),
                de.augmentia.oc.engine.core.model.Option.none(), "h", Instant.now());
        store.append(sid, rec);
        assertThat(store.byMessage(sid, mid)).hasSize(1);
        assertThat(store.bySession(sid, 10)).hasSize(1);
    }

    @Test
    void inMemoryMemoryStoreUpsertAndQuery() {
        InMemoryMemoryStore store = new InMemoryMemoryStore();
        Fact f = new Fact("f1", "project", "has", "qoc", Instant.now(), null, null, List.of("src"), 0.9, 0);
        store.upsert(f);
        assertThat(store.query(new de.augmentia.oc.engine.core.model.MemoryQuery("project", null, null, null, 10))).hasSize(1);
        assertThat(store.checkAnswerable("project qoc")).isNotNull();
    }
}
