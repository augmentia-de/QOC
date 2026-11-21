package de.augmentia.oc.engine.core.contract.tool;

import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.ToolName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolOptionsUndoTest {

    @Test
    void defaultsHaveNoUndoPattern() {
        assertThat(ToolOptions.defaults().undo().isEmpty()).isTrue();
    }

    @Test
    void withUndoSurvivesStrictAndApprovalMessageCopies() {
        UndoSpec spec = UndoSpec.of(new ToolName("compensate"),
                (input, output, metadata) -> JsonValue.of(Map.of()));

        ToolOptions options = new ToolOptions(Option.none(), Option.none(), true, false)
                .withUndo(spec)
                .withStrict(true)
                .withApprovalMessage(value -> "approve");

        assertThat(options.undo().orElseThrow()).isSameAs(spec);
        assertThat(options.strict()).isTrue();
        assertThat(options.approvalMessage().isPresent()).isTrue();
    }

    @Test
    void nullGuardDefaultsToAllowAll() {
        UndoSpec spec = new UndoSpec(new ToolName("compensate"),
                (input, output, metadata) -> JsonValue.ofNull(), null);

        assertThat(spec.guard().allow(JsonValue.ofNull(), JsonValue.ofNull(), Map.of())).isTrue();
    }

    @Test
    void nullCompensatingToolNameIsRejected() {
        assertThatThrownBy(() -> new UndoSpec(null,
                (input, output, metadata) -> JsonValue.ofNull(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("compensatingToolName");
    }

    @Test
    void nullMapperIsRejected() {
        assertThatThrownBy(() -> new UndoSpec(new ToolName("compensate"), null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mapper");
    }

    @Test
    void withUndoNullClearsThePattern() {
        UndoSpec spec = UndoSpec.of(new ToolName("compensate"),
                (input, output, metadata) -> JsonValue.ofNull());

        ToolOptions options = ToolOptions.defaults().withUndo(spec).withUndo(null);

        assertThat(options.undo().isEmpty()).isTrue();
    }
}
