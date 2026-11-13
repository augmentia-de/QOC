package de.augmentia.oc.engine.core.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ArtifactRefTest {

    @Test
    void createsValidRef() {
        SessionId session = SessionId.create();
        ArtifactRef ref = new ArtifactRef(session, "art-1", "text/plain", "abc123", 42);
        assertThat(ref.session()).isEqualTo(session);
        assertThat(ref.id()).isEqualTo("art-1");
        assertThat(ref.mediaType()).isEqualTo("text/plain");
        assertThat(ref.sha256()).isEqualTo("abc123");
        assertThat(ref.size()).isEqualTo(42);
        assertThat(ref.uri()).isEqualTo("artifact://" + session.value() + "/art-1");
    }

    @Test
    void defaultsMediaType() {
        SessionId session = SessionId.create();
        ArtifactRef ref = new ArtifactRef(session, "art-1", "", "abc", 0);
        assertThat(ref.mediaType()).isEqualTo("application/octet-stream");
    }

    @Test
    void rejectsNullSession() {
        assertThatThrownBy(() -> new ArtifactRef(null, "id", "text/plain", "abc", 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsBlankId() {
        SessionId session = SessionId.create();
        assertThatThrownBy(() -> new ArtifactRef(session, "  ", "text/plain", "abc", 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsBlankSha() {
        SessionId session = SessionId.create();
        assertThatThrownBy(() -> new ArtifactRef(session, "id", "text/plain", "", 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNegativeSize() {
        SessionId session = SessionId.create();
        assertThatThrownBy(() -> new ArtifactRef(session, "id", "text/plain", "abc", -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parsesUri() {
        SessionId session = SessionId.create();
        ArtifactRef.Parsed parsed = ArtifactRef.parse("artifact://" + session.value() + "/art-1");
        assertThat(parsed.session()).isEqualTo(session);
        assertThat(parsed.id()).isEqualTo("art-1");
    }

    @Test
    void parseRejectsInvalidUri() {
        assertThatThrownBy(() -> ArtifactRef.parse("not-an-artifact"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ArtifactRef.parse("artifact://session/"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ArtifactRef.parse("artifact://session/a/b"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
