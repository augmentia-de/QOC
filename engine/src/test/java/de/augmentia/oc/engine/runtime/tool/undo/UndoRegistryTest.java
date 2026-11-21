package de.augmentia.oc.engine.runtime.tool.undo;

import de.augmentia.oc.engine.core.contract.tool.ToolDefinition;
import de.augmentia.oc.engine.core.contract.tool.ToolOptions;
import de.augmentia.oc.engine.core.contract.tool.ToolOutcome;
import de.augmentia.oc.engine.core.contract.tool.ToolResult;
import de.augmentia.oc.engine.core.contract.tool.UndoSpec;
import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.json.Schema;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.ToolName;
import de.augmentia.oc.engine.runtime.tool.registry.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UndoRegistryTest {

    @Test
    void indexesOnlyToolsWithUndoPatterns() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(definition("forward", true));
        registry.register(definition("plain", false));

        UndoRegistry undo = new UndoRegistry();
        undo.rebuildFrom(registry);

        assertThat(undo.size()).isEqualTo(1);
        assertThat(undo.hasUndo(new ToolName("forward"))).isTrue();
        assertThat(undo.hasUndo(new ToolName("plain"))).isFalse();
        assertThat(undo.specOf(new ToolName("forward")).isPresent()).isTrue();
        assertThat(undo.specOf(new ToolName("plain")).isEmpty()).isTrue();
        assertThat(undo.snapshot()).containsKey(new ToolName("forward"));
    }

    @Test
    void rebuildReplacesPreviousEntries() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(definition("forward", true));

        UndoRegistry undo = new UndoRegistry();
        undo.rebuildFrom(registry);
        assertThat(undo.size()).isEqualTo(1);

        ToolRegistry replacement = new ToolRegistry();
        replacement.register(definition("other", true));
        undo.rebuildFrom(replacement);

        assertThat(undo.size()).isEqualTo(1);
        assertThat(undo.hasUndo(new ToolName("forward"))).isFalse();
        assertThat(undo.hasUndo(new ToolName("other"))).isTrue();
    }

    @Test
    void registerRejectsNullDefinition() {
        UndoRegistry undo = new UndoRegistry();
        assertThatThrownBy(() -> undo.register(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rebuildRejectsNullRegistry() {
        UndoRegistry undo = new UndoRegistry();
        assertThatThrownBy(() -> undo.rebuildFrom(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static ToolDefinition definition(String name, boolean withUndo) {
        ToolOptions options = ToolOptions.defaults();
        if (withUndo) {
            options = options.withUndo(UndoSpec.of(new ToolName("compensate"),
                    (input, output, metadata) -> JsonValue.ofNull()));
        }
        return new ToolDefinition(new ToolName(name), "test", Schema.obj(Map.of()),
                Option.none(), (input, context) -> new ToolOutcome.Ok(ToolResult.of("ok")), options);
    }
}
