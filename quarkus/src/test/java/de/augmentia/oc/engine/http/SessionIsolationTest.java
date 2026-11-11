package de.augmentia.oc.engine.http;

import de.augmentia.oc.engine.core.model.SessionId;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class SessionIsolationTest {

    @Inject
    Engine engine;

    @Test
    void listSessionsIsScopedToOwnerAndAdminSeesAll() {
        String alice = "alice-" + UUID.randomUUID();
        String bob = "bob-" + UUID.randomUUID();
        SessionId a = null;
        SessionId b = null;
        try {
            a = engine.createSession("build", alice, "workspace", false, List.of("user"));
            b = engine.createSession("build", bob, "workspace", false, List.of("user"));

            assertThat(engine.listSessions(alice, false)).extracting(info -> info.id().value())
                    .contains(a.value()).doesNotContain(b.value());
            assertThat(engine.listSessions(bob, false)).extracting(info -> info.id().value())
                    .contains(b.value()).doesNotContain(a.value());
            assertThat(engine.listSessions(alice, true)).extracting(info -> info.id().value())
                    .contains(a.value(), b.value());

            assertThat(engine.isOwner(engine.session(a).orElseThrow(), alice)).isTrue();
            assertThat(engine.isOwner(engine.session(a).orElseThrow(), bob)).isFalse();
        } finally {
            if (a != null) engine.deleteSession(a);
            if (b != null) engine.deleteSession(b);
        }
    }
}
