package de.augmentia.oc.engine.runtime;

import de.augmentia.oc.engine.runtime.compaction.CtxNudge;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CtxNudgeTest {

    @Test
    void followsTemplateWhenAllSectionsPresent() {
        String response = "## Objective\n- x\n## Requirements\n- (none)\n## Decisions\n- (none)\n"
                + "## Work State\n### Completed\n- (none)\n### Active\n- (none)\n### Blocked\n- (none)\n"
                + "## Next Move\n1. do it";
        assertThat(CtxNudge.followsTemplate(response)).isTrue();
        assertThat(CtxNudge.nudgeIfNeeded(response)).isNull();
    }

    @Test
    void nudgeWhenSectionsMissing() {
        assertThat(CtxNudge.followsTemplate("just some text")).isFalse();
        assertThat(CtxNudge.nudgeIfNeeded("just some text")).isEqualTo(CtxNudge.prompt());
    }

    @Test
    void blankResponseNeedsNudge() {
        assertThat(CtxNudge.followsTemplate(null)).isFalse();
        assertThat(CtxNudge.followsTemplate("")).isFalse();
        assertThat(CtxNudge.followsTemplate("   ")).isFalse();
    }
}
