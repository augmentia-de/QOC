package de.augmentia.oc.engine.core;
import de.augmentia.oc.engine.core.eval.CacheAffinity;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.SessionId;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CacheAffinityTest {

    private static final String HEX = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    void normalSessionUsesItsOwnId() {
        SessionId id = SessionId.create();
        assertThat(CacheAffinity.affinity(id, Option.none(), Option.none())).isEqualTo(id.value());
    }

    @Test
    void childSessionUsesTheParentAffinity() {
        SessionId child = SessionId.create();
        SessionId parent = SessionId.create();
        SessionId fork = SessionId.create();
        assertThat(CacheAffinity.affinity(child, Option.some(parent), Option.some(fork)))
                .isEqualTo(parent.value());
    }

    @Test
    void forkUsesTheSourceSessionAffinity() {
        SessionId fork = SessionId.create();
        SessionId source = SessionId.create();
        assertThat(CacheAffinity.affinity(fork, Option.none(), Option.some(source)))
                .isEqualTo(source.value());
    }

    @Test
    void affinityIsSessionStable() {
        SessionId id = SessionId.create();
        String first = CacheAffinity.affinity(id, Option.none(), Option.none());
        String second = CacheAffinity.affinity(id, Option.none(), Option.none());
        assertThat(second).isEqualTo(first);
    }

    @Test
    void threeHeadersCarryTheSameValue() {
        String affinity = "ses_" + HEX;
        var headers = CacheAffinity.headers(affinity);
        assertThat(headers).hasSize(3);
        assertThat(headers.get(CacheAffinity.HEADER_AFFINITY)).isEqualTo(affinity);
        assertThat(headers.get(CacheAffinity.HEADER_SESSION_ID)).isEqualTo(affinity);
        assertThat(headers.get(CacheAffinity.HEADER_OPENCODE)).isEqualTo(affinity);
        assertThatThrownBy(() -> CacheAffinity.headers(" ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void promptCacheKeyStripsThePrefixOnlyForSessionForm() {
        assertThat(CacheAffinity.promptCacheKey("ses_" + HEX)).isEqualTo(HEX);
        assertThat(CacheAffinity.promptCacheKey("project-42")).isEqualTo("project-42");
        assertThat(CacheAffinity.promptCacheKey("ses_short")).isEqualTo("ses_short");
        assertThat(CacheAffinity.promptCacheKey("ses_" + "Z".repeat(64))).isEqualTo("ses_" + "Z".repeat(64));
        assertThatThrownBy(() -> CacheAffinity.promptCacheKey(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
