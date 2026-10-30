package de.augmentia.oc.engine.core;

import de.augmentia.oc.engine.core.eval.ToolCallLeak;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ToolCallLeakTest {

    @Test
    void detectsDeepSeekMarkup() {
        assertThat(ToolCallLeak.contains("<|DSML| calls>")).isTrue();
        assertThat(ToolCallLeak.contains("<\uFF5CDSML\uFF5C calls>")).isTrue();
    }

    @Test
    void detectsGenericToolCallMarkup() {
        assertThat(ToolCallLeak.contains("<|tool\u2581call\u2581begin|>")).isTrue();
        assertThat(ToolCallLeak.contains("[TOOL_CALLS] read")).isTrue();
    }

    @Test
    void ignoresPlainText() {
        assertThat(ToolCallLeak.contains("The step limit is reached.")).isFalse();
        assertThat(ToolCallLeak.contains("")).isFalse();
        assertThat(ToolCallLeak.contains(null)).isFalse();
    }
}
