package de.augmentia.oc.engine.core;

import de.augmentia.oc.engine.core.model.ApprovalTier;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApprovalTierTest {

    @Test
    void classifiesBuiltInToolsByCapability() {
        assertThat(ApprovalTier.forTool("read")).isEqualTo(ApprovalTier.READ);
        assertThat(ApprovalTier.forTool("grep")).isEqualTo(ApprovalTier.READ);
        assertThat(ApprovalTier.forTool("edit")).isEqualTo(ApprovalTier.WRITE);
        assertThat(ApprovalTier.forTool("write")).isEqualTo(ApprovalTier.WRITE);
        assertThat(ApprovalTier.forTool("shell")).isEqualTo(ApprovalTier.EXEC);
        assertThat(ApprovalTier.forTool("unknown")).isEqualTo(ApprovalTier.EXEC);
    }
}
