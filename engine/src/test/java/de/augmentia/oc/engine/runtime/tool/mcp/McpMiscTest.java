package de.augmentia.oc.engine.runtime.tool.mcp;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class McpMiscTest {

    @Test
    void mcpFailure() {
        McpFailure f = new McpFailure("msg");
        assertThat(f.getMessage()).isEqualTo("msg");
        McpFailure f2 = new McpFailure("msg2", new RuntimeException("cause"));
        assertThat(f2.getCause()).isNotNull();
    }

    @Test
    void mcpNeedsAuth() {
        McpNeedsAuth a = new McpNeedsAuth("auth");
        assertThat(a).isInstanceOf(McpFailure.class);
        assertThat(a.getMessage()).isEqualTo("auth");
    }

    @Test
    void mcpServerStatus() {
        assertThat(McpServerStatus.values()).contains(McpServerStatus.CONNECTED, McpServerStatus.NEEDS_AUTH);
    }

    @Test
    void mcpResourceRecords() {
        McpResource.Resource r = new McpResource.Resource(null, null, null, null, null);
        assertThat(r.server()).isEmpty();
        McpResource.Template t = new McpResource.Template(null, null, null, null, null);
        assertThat(t.uriTemplate()).isEmpty();
        McpResource.Content c = new McpResource.Content(null, null, null, null);
        assertThat(c.text()).isEmpty();
    }
}
