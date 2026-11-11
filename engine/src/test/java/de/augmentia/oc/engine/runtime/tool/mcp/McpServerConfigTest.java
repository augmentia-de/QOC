package de.augmentia.oc.engine.runtime.tool.mcp;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpServerConfigTest {

    @Test
    void localDefaults() {
        var c = new McpServerConfig.Local("srv", List.of("echo"), null, null, false, false, 0, 0, 0);
        assertThat(c.name()).isEqualTo("srv");
        assertThat(c.startupTimeoutMs()).isEqualTo(10_000);
        assertThat(c.catalogTimeoutMs()).isEqualTo(10_000);
        assertThat(c.executionTimeoutMs()).isEqualTo(30_000);
        assertThat(c.command()).containsExactly("echo");
        assertThat(c.cwd()).isEmpty();
        assertThat(c.environment()).isEmpty();
    }

    @Test
    void localRejectsBlankName() {
        assertThatThrownBy(() -> new McpServerConfig.Local("", List.of(), "", Map.of(), false, false, 1, 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void remoteDefaults() {
        var c = new McpServerConfig.Remote("srv", "http://x", null, false, false, false, 0, 0, 0);
        assertThat(c.url()).isEqualTo("http://x");
        assertThat(c.startupTimeoutMs()).isEqualTo(10_000);
        assertThat(c.headers()).isEmpty();
    }

    @Test
    void remoteRejectsBlankUrl() {
        assertThatThrownBy(() -> new McpServerConfig.Remote("srv", "  ", Map.of(), false, false, false, 1, 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void remoteRejectsBlankName() {
        assertThatThrownBy(() -> new McpServerConfig.Remote("", "http://x", Map.of(), false, false, false, 1, 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
