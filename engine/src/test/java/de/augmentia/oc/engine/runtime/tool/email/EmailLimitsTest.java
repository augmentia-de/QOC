package de.augmentia.oc.engine.runtime.tool.email;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmailLimitsTest {

    @Test
    void defaultsAreConservative() {
        EmailLimits limits = EmailLimits.defaults();
        assertThat(limits.maxRecipients()).isEqualTo(10);
        assertThat(limits.maxAttachments()).isEqualTo(5);
        assertThat(limits.attachmentsEnabled()).isTrue();
        assertThat(limits.allowedRecipients()).isEmpty();
    }

    @Test
    void emptyAllowListAllowsEverythingExceptDenied() {
        EmailLimits limits = new EmailLimits(5, 0, 1024, 100, 50,
                List.of(), List.of("blocked@example.com"), 5, false);
        assertThat(limits.allows("any@example.com")).isTrue();
        assertThat(limits.allows("blocked@example.com")).isFalse();
        assertThat(limits.allows(null)).isFalse();
        assertThat(limits.allows(" ")).isFalse();
    }

    @Test
    void allowListMatchesExactAndWildcards() {
        EmailLimits limits = new EmailLimits(5, 0, 1024, 100, 50,
                List.of("test@schippel.info", "*@example.com"), List.of(), 5, false);
        assertThat(limits.allows("test@schippel.info")).isTrue();
        assertThat(limits.allows("any@example.com")).isTrue();
        assertThat(limits.allows("other@schippel.info")).isFalse();
        assertThat(limits.allows("TEST@SCHIPPEL.INFO")).isTrue();
    }

    @Test
    void denyWinsOverAllow() {
        EmailLimits limits = new EmailLimits(5, 0, 1024, 100, 50,
                List.of("*@example.com"), List.of("bad@example.com"), 5, false);
        assertThat(limits.allows("good@example.com")).isTrue();
        assertThat(limits.allows("bad@example.com")).isFalse();
    }

    @Test
    void nullValuesAreNormalized() {
        EmailLimits limits = new EmailLimits(-1, -1, 0, 0, 0, null, null, -1, true);
        assertThat(limits.maxRecipients()).isEqualTo(10);
        assertThat(limits.maxAttachments()).isEqualTo(5);
        assertThat(limits.maxAttachmentBytes()).isEqualTo(10L * 1024 * 1024);
        assertThat(limits.maxBodyChars()).isEqualTo(50_000);
        assertThat(limits.maxSubjectChars()).isEqualTo(200);
        assertThat(limits.maxPerHour()).isEqualTo(20);
        assertThat(limits.allowedRecipients()).isEmpty();
    }
}