package de.augmentia.oc.engine.runtime;

import de.augmentia.oc.engine.core.port.world.HttpTransport;
import de.augmentia.oc.engine.runtime.tool.mcp.McpServerConfig;
import de.augmentia.oc.engine.runtime.tool.mcp.McpRegistrar;
import de.augmentia.oc.engine.runtime.tool.mcp.McpServerManager;
import de.augmentia.oc.engine.runtime.tool.mcp.McpServerStatus;
import de.augmentia.oc.engine.runtime.tool.registry.ToolRegistry;
import de.augmentia.oc.engine.testing.ScriptedHttp;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpServerManagerTest {

    @Test
    void addDisabledServerDoesNothing() {
        ToolRegistry registry = new ToolRegistry();
        McpServerManager manager = new McpServerManager(registry, null, null);

        manager.add(new McpServerConfig.Remote("github", "https://example.test/mcp", Map.of(),
                false, true, true, 5_000, 5_000, 5_000));

        assertThat(manager.status("github")).isEqualTo(McpServerStatus.DISABLED);
        assertThat(registry.list()).isEmpty();
        assertThat(manager.serverNames()).containsExactly("github");
    }

    @Test
    void reloadRediscovers() {
        String init = "{\"jsonrpc\":\"2.0\",\"id\":0,\"result\":{\"protocolVersion\":\"2025-11-25\"}}";
        String tools = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":["
                + "{\"name\":\"search\",\"description\":\"s\",\"inputSchema\":{\"type\":\"object\"}}]}}";
        ScriptedHttp transport = new ScriptedHttp(
                new ScriptedHttp.Response(200, Map.of(), init.getBytes()),
                new ScriptedHttp.Response(200, Map.of(), tools.getBytes()),
                new ScriptedHttp.Response(200, Map.of(), init.getBytes()),
                new ScriptedHttp.Response(200, Map.of(), tools.getBytes()));
        ToolRegistry registry = new ToolRegistry();
        McpServerManager manager = new McpServerManager(registry, transport, null);
        manager.add(new McpServerConfig.Remote("github", "https://example.test/mcp", Map.of(),
                false, false, true, 5_000, 5_000, 5_000));

        assertThat(manager.status("github")).isEqualTo(McpServerStatus.CONNECTED);
        assertThat(registry.list()).hasSize(1);

        manager.reload("github");

        assertThat(manager.status("github")).isEqualTo(McpServerStatus.CONNECTED);
        assertThat(registry.list()).hasSize(1);
    }

    @Test
    void unknownServerReturnsDisabled() {
        McpServerManager manager = new McpServerManager(new ToolRegistry(), null, null);
        assertThat(manager.status("nope")).isEqualTo(McpServerStatus.DISABLED);
        assertThat(manager.error("nope")).isNull();
        assertThat(manager.discovered("nope")).isEmpty();
    }

    @Test
    void discoverAllSkipsDisabledServers() {
        ToolRegistry registry = new ToolRegistry();
        McpServerManager manager = new McpServerManager(registry, null, null);
        manager.add(new McpServerConfig.Remote("github", "https://example.test/mcp", Map.of(),
                false, true, true, 5_000, 5_000, 5_000));
        manager.discoverAll();
        assertThat(registry.list()).isEmpty();
    }

    @Test
    void discoveredToolsAndClientAreExposed() {
        ScriptedHttp transport = scriptedDiscovery();
        ToolRegistry registry = new ToolRegistry();
        McpServerManager manager = new McpServerManager(registry, transport, null);
        manager.add(remote("github"));

        assertThat(manager.discovered("github")).extracting(McpRegistrar.McpToolSpec::name)
                .containsExactly("search");
        assertThat(manager.discoveredAll()).containsOnlyKeys("github");
        assertThat(manager.client("github")).isNotNull();
        assertThat(manager.client("unknown")).isNull();
        assertThat(manager.discovered("unknown")).isEmpty();
        assertThat(manager.discoveredAll()).doesNotContainKey("unknown");
        assertThat(manager.serverNames()).containsExactly("github");
    }

    @Test
    void removeClosesRegistrationsAndForgetsServer() {
        ToolRegistry registry = new ToolRegistry();
        McpServerManager manager = new McpServerManager(registry, scriptedDiscovery(), null);
        manager.add(remote("github"));
        assertThat(registry.list()).hasSize(1);

        manager.remove("github");

        assertThat(registry.list()).isEmpty();
        assertThat(manager.serverNames()).isEmpty();
        assertThat(manager.status("github")).isEqualTo(McpServerStatus.DISABLED);
        assertThat(manager.client("github")).isNull();
        assertThat(manager.discovered("github")).isEmpty();
        manager.remove("github"); // idempotent
        manager.remove(null);
    }

    @Test
    void failedDiscoveryMarksServerAsFailed() {
        ScriptedHttp transport = new ScriptedHttp(ScriptedHttp.Response.text(500, "boom"));
        McpServerManager manager = new McpServerManager(new ToolRegistry(), transport, null);

        manager.add(remote("broken"));

        assertThat(manager.status("broken")).isEqualTo(McpServerStatus.FAILED);
        assertThat(manager.error("broken")).isNotBlank();
        assertThat(manager.discovered("broken")).isEmpty();
    }

    @Test
    void authenticateRejectsServersThatAreNotDiscoveryClients() {
        McpServerManager manager = new McpServerManager(new ToolRegistry(), null, null);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> manager.authenticate("missing", "code", "http://localhost/cb", "id", "secret"))
                .isInstanceOf(de.augmentia.oc.engine.runtime.tool.mcp.McpFailure.class);
    }

    private static McpServerConfig remote(String name) {
        return new McpServerConfig.Remote(name, "https://example.test/mcp", Map.of(),
                false, false, true, 5_000, 5_000, 5_000);
    }

    private static ScriptedHttp scriptedDiscovery() {
        String init = "{\"jsonrpc\":\"2.0\",\"id\":0,\"result\":{\"protocolVersion\":\"2025-11-25\"}}";
        String tools = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":["
                + "{\"name\":\"search\",\"description\":\"s\",\"inputSchema\":{\"type\":\"object\"}}]}}";
        return new ScriptedHttp(
                new ScriptedHttp.Response(200, Map.of(), init.getBytes()),
                new ScriptedHttp.Response(200, Map.of(), tools.getBytes()));
    }
}
