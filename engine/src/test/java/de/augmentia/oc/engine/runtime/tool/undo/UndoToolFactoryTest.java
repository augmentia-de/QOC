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
import de.augmentia.oc.engine.testing.InMemoryConversation;
import de.augmentia.oc.engine.testing.InMemoryJournal;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UndoToolFactoryTest {

    @Test
    void createsUndoToolWithExpectedMetadata() {
        UndoSpec spec = UndoSpec.of(new ToolName("compensate"),
                (input, output, metadata) -> JsonValue.ofNull());
        ToolDefinition forward = new ToolDefinition(new ToolName("worktree_create"), "create",
                Schema.obj(Map.of()), Option.none(),
                (input, context) -> new ToolOutcome.Ok(ToolResult.of("ok")),
                ToolOptions.defaults().withUndo(spec));

        ToolDefinition undo = UndoToolFactory.create(forward, spec, runtime());

        assertThat(undo.effectiveName()).isEqualTo("undo_worktree_create");
        assertThat(undo.options().permission().orElseThrow()).isEqualTo("undo_worktree_create");
        assertThat(undo.options().codemode()).isTrue();
        assertThat(undo.options().undo().isEmpty()).isTrue();
        assertThat(undo.description()).contains("worktree_create");
        assertThat(undo.inputSchema()).isInstanceOf(Schema.Obj.class);
    }

    @Test
    void namespacedForwardDoesNotDoubleTheNamespace() {
        UndoSpec spec = UndoSpec.of(new ToolName("compensate"),
                (input, output, metadata) -> JsonValue.ofNull());
        ToolDefinition forward = new ToolDefinition(new ToolName("read"), "read",
                Schema.obj(Map.of()), Option.none(),
                (input, context) -> new ToolOutcome.Ok(ToolResult.of("ok")),
                new ToolOptions(Option.of("mcp"), Option.none(), true, false).withUndo(spec));

        ToolDefinition undo = UndoToolFactory.create(forward, spec, runtime());

        assertThat(undo.effectiveName()).isEqualTo("undo_mcp_read");
        assertThat(undo.options().permission().orElseThrow()).isEqualTo("undo_mcp_read");
    }

    @Test
    void rejectsNullArguments() {
        UndoSpec spec = UndoSpec.of(new ToolName("compensate"),
                (input, output, metadata) -> JsonValue.ofNull());
        ToolDefinition forward = new ToolDefinition(new ToolName("forward"), "forward",
                Schema.obj(Map.of()), Option.none(),
                (input, context) -> new ToolOutcome.Ok(ToolResult.of("ok")),
                ToolOptions.defaults().withUndo(spec));
        UndoRuntime runtime = runtime();

        assertThatThrownBy(() -> UndoToolFactory.create(null, spec, runtime))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> UndoToolFactory.create(forward, null, runtime))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> UndoToolFactory.create(forward, spec, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static UndoRuntime runtime() {
        return new UndoRuntime(new InMemoryConversation(), new InMemoryJournal(),
                new ToolRegistry(), new UndoRegistry(), () -> null, null);
    }
}
