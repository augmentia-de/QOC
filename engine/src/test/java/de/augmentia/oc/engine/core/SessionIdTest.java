package de.augmentia.oc.engine.core;

import de.augmentia.oc.engine.core.model.SessionId;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SessionIdTest {

    @Test
    void topLevelSessionIsNotAChild() {
        SessionId id = SessionId.create();

        assertThat(id.isChild()).isFalse();
        assertThat(id.parent().isPresent()).isFalse();
    }

    @Test
    void childCarriesItsParent() {
        SessionId parent = SessionId.create();
        SessionId child = SessionId.create(parent);

        assertThat(child.isChild()).isTrue();
        assertThat(child.parent().orElseThrow()).isEqualTo(parent);
    }

    @Test
    void grandchildCarriesItsDirectParent() {
        SessionId root = SessionId.create();
        SessionId child = SessionId.create(root);
        SessionId grandchild = SessionId.create(child);

        assertThat(grandchild.isChild()).isTrue();
        assertThat(grandchild.parent().orElseThrow()).isEqualTo(child);
        assertThat(grandchild.parent().orElseThrow().parent().orElseThrow()).isEqualTo(root);
    }
}
