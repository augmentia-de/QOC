package de.augmentia.oc.engine.runtime.tool.mcp;

import de.augmentia.oc.engine.core.json.Schema;
import de.augmentia.oc.engine.runtime.tool.registry.ToolRegistry;
import de.augmentia.oc.engine.runtime.tool.registry.Registration;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpRegistrarTest {

    @Test
    void registerValidatesArgs() {
        McpRegistrar r = new McpRegistrar();
        assertThatThrownBy(() -> r.register(null, "srv", List.of(), (c, a) -> null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> r.register(new ToolRegistry(), "", List.of(), (c, a) -> null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> r.register(new ToolRegistry(), "srv", List.of(), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void registerRegistersTools() {
        McpRegistrar r = new McpRegistrar();
        ToolRegistry reg = new ToolRegistry();
        McpClient client = (name, args) -> null;
        var spec = new McpRegistrar.McpToolSpec("tool", "desc", Schema.str());
        List<Registration> regs = r.register(reg, "srv", List.of(spec), client);
        assertThat(regs).hasSize(1);
        assertThat(reg.list()).hasSize(1);
        assertThat(reg.list().get(0).effectiveName()).isEqualTo("srv_tool");
    }

    @Test
    void registerSkipsNullToolList() {
        McpRegistrar r = new McpRegistrar();
        ToolRegistry reg = new ToolRegistry();
        McpClient client = (name, args) -> null;
        List<Registration> regs = r.register(reg, "srv", null, client);
        assertThat(regs).isEmpty();
        assertThat(reg.list()).isEmpty();
    }

    @Test
    void mcpToolSpecValidates() {
        assertThatThrownBy(() -> new McpRegistrar.McpToolSpec("", "d", Schema.str()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new McpRegistrar.McpToolSpec("t", "d", null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
