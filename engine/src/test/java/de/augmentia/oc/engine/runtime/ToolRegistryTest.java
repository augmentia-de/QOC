package de.augmentia.oc.engine.runtime;

import de.augmentia.oc.engine.core.contract.tool.ToolDefinition;
import de.augmentia.oc.engine.core.contract.tool.ToolOptions;
import de.augmentia.oc.engine.core.contract.tool.ToolOutcome;
import de.augmentia.oc.engine.core.contract.tool.ToolResult;
import de.augmentia.oc.engine.core.json.Schema;
import de.augmentia.oc.engine.core.model.Effect;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.Rule;
import de.augmentia.oc.engine.core.model.ToolName;
import de.augmentia.oc.engine.runtime.tool.registry.Registration;
import de.augmentia.oc.engine.runtime.tool.registry.ToolRegistry;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ToolRegistryTest {

    @Test
    void newestRegistrationWins() {
        ToolRegistry registry = new ToolRegistry();
        ToolDefinition first = definition("read", "v1", ToolOptions.defaults());
        ToolDefinition second = definition("read", "v2", ToolOptions.defaults());

        registry.register(first);
        registry.register(second);

        assertThat(registry.list()).containsExactly(second);
        assertThat(registry.size()).isEqualTo(1);
        assertThat(registry.epoch()).isEqualTo(2);
    }

    @Test
    void closingRestoresThePredecessor() {
        ToolRegistry registry = new ToolRegistry();
        ToolDefinition first = definition("read", "v1", ToolOptions.defaults());
        ToolDefinition second = definition("read", "v2", ToolOptions.defaults());
        registry.register(first);
        Registration registration = registry.register(second);

        registration.close();
        assertThat(registration.active()).isFalse();
        assertThat(registry.list()).containsExactly(first);

        registration.close();
        assertThat(registry.epoch()).isEqualTo(3);
    }

    @Test
    void closingTheLastRegistrationRemovesTheName() {
        ToolRegistry registry = new ToolRegistry();
        Registration registration = registry.register(definition("read", "v1", ToolOptions.defaults()));
        registration.close();
        assertThat(registry.list()).isEmpty();
    }

    @Test
    void namespacedToolsAreSeparate() {
        ToolRegistry registry = new ToolRegistry();
        ToolDefinition plain = definition("read", "plain", ToolOptions.defaults());
        ToolDefinition namespaced = definition("read", "fs",
                new ToolOptions(Option.some("fs"), Option.none(), true, false));

        registry.register(plain);
        registry.register(namespaced);

        assertThat(registry.list()).containsExactly(plain, namespaced);
        assertThat(registry.snapshot().definitions()).containsOnlyKeys(new ToolName("read"), new ToolName("fs_read"));
    }

    @Test
    void snapshotFreezesTheDefinitions() {
        ToolRegistry registry = new ToolRegistry();
        ToolDefinition first = definition("read", "v1", ToolOptions.defaults());
        registry.register(first);
        var snapshot = registry.snapshot();

        registry.register(definition("read", "v2", ToolOptions.defaults()));

        assertThat(snapshot.require(new ToolName("read"))).isEqualTo(first);
        assertThat(registry.snapshot().require(new ToolName("read")).description()).isEqualTo("v2");
    }

    @Test
    void denyRuleHidesTheToolFromTheCatalog() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(definition("read", "read", ToolOptions.defaults()));
        registry.register(definition("write", "write", new ToolOptions(
                Option.none(), Option.some("edit"), true, false)));

        var snapshot = registry.snapshot(List.of(new Rule("read", "*", Effect.DENY)));
        assertThat(snapshot.list()).extracting(ToolDefinition::name).doesNotContain(new ToolName("read"));
        assertThat(snapshot.list()).extracting(ToolDefinition::name).contains(new ToolName("write"));
    }

    @Test
    void deniedExecuteRemovesCodeModeTools() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(definition("read", "code", new ToolOptions(Option.none(), Option.none(), true, false)));
        registry.register(definition("shell", "direct", new ToolOptions(Option.none(), Option.none(), false, false)));

        var snapshot = registry.snapshot(List.of(new Rule("execute", "*", Effect.DENY)));

        assertThat(snapshot.list()).extracting(ToolDefinition::name).containsExactly(new ToolName("shell"));
    }

    @Test
    void registrationExposesTheEffectiveName() {
        ToolRegistry registry = new ToolRegistry();
        Registration registration = registry.register(definition("read", "fs",
                new ToolOptions(Option.some("fs"), Option.none(), true, false)));
        assertThat(registration.name()).isEqualTo(new ToolName("fs_read"));
    }

    @Test
    void reloadKeepsPrecedence() {
        ToolRegistry registry = new ToolRegistry();
        ToolDefinition first = definition("read", "v1", ToolOptions.defaults());
        ToolDefinition second = definition("read", "v2", ToolOptions.defaults());
        registry.register(first);
        registry.register(second);

        assertThat(registry.reload()).containsExactly(second);
    }

    private static ToolDefinition definition(String name, String description, ToolOptions options) {
        return new ToolDefinition(new ToolName(name), description, Schema.obj(Map.of()), Option.none(),
                (input, context) -> new ToolOutcome.Ok(ToolResult.of("ok")), options);
    }
}
