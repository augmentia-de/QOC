package de.augmentia.oc.engine.runtime.tool.subagent;

import de.augmentia.oc.engine.core.model.SessionId;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SubagentSessionRegistryTest {

    @Test
    void registerAndChildren() {
        SubagentSessionRegistry r = new InMemorySubagentSessionRegistry();
        SessionId parent = SessionId.create();
        SessionId child = SessionId.create();
        r.registerChild(parent, child);
        assertThat(r.children(parent)).contains(child);
        assertThat(r.descendants(parent)).contains(child);
    }

    @Test
    void descendantsRecursive() {
        SubagentSessionRegistry r = new InMemorySubagentSessionRegistry();
        SessionId a = SessionId.create();
        SessionId b = SessionId.create();
        SessionId c = SessionId.create();
        r.registerChild(a, b);
        r.registerChild(b, c);
        assertThat(r.descendants(a)).containsExactlyInAnyOrder(b, c);
    }

    @Test
    void removeCleansUp() {
        SubagentSessionRegistry r = new InMemorySubagentSessionRegistry();
        SessionId parent = SessionId.create();
        SessionId child = SessionId.create();
        r.registerChild(parent, child);
        r.remove(child);
        assertThat(r.children(parent)).doesNotContain(child);
        assertThat(r.children(child)).isEmpty();
    }

    @Test
    void nullSafe() {
        SubagentSessionRegistry r = new InMemorySubagentSessionRegistry();
        r.registerChild(null, SessionId.create());
        r.registerChild(SessionId.create(), null);
        assertThat(r.children(null)).isEmpty();
        assertThat(r.descendants(null)).isEmpty();
        r.remove(null);
    }

    @Test
    void emptyChildren() {
        SubagentSessionRegistry r = new InMemorySubagentSessionRegistry();
        assertThat(r.children(SessionId.create())).isEqualTo(Set.of());
    }
}
