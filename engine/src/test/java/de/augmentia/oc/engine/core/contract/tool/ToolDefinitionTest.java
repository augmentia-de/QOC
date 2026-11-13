package de.augmentia.oc.engine.core.contract.tool;

import de.augmentia.oc.engine.core.json.Schema;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.ToolName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolDefinitionTest {

    private ToolDefinition def(String name, Schema input, ToolOptions options) {
        return new ToolDefinition(new ToolName(name), "desc", input, Option.none(),
                (in, ctx) -> new ToolOutcome.Ok(ToolResult.of("ok")), options);
    }

    @Test
    void createsValidDefinition() {
        ToolDefinition td = def("read", Schema.str(), ToolOptions.defaults());
        assertThat(td.name().value()).isEqualTo("read");
        assertThat(td.description()).isEqualTo("desc");
        assertThat(td.inputSchema()).isEqualTo(Schema.str());
        assertThat(td.outputSchema()).isEqualTo(Option.none());
        assertThat(td.options()).isEqualTo(ToolOptions.defaults());
    }

    @Test
    void rejectsNullName() {
        assertThatThrownBy(() -> new ToolDefinition(null, "desc", Schema.str(), Option.none(),
                (in, ctx) -> new ToolOutcome.Ok(ToolResult.of("ok")), ToolOptions.defaults()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNullInputSchema() {
        assertThatThrownBy(() -> new ToolDefinition(new ToolName("t"), "desc", null, Option.none(),
                (in, ctx) -> new ToolOutcome.Ok(ToolResult.of("ok")), ToolOptions.defaults()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNullExecutor() {
        assertThatThrownBy(() -> new ToolDefinition(new ToolName("t"), "desc", Schema.str(), Option.none(),
                null, ToolOptions.defaults()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void defaultsDescriptionAndOptions() {
        ToolDefinition td = new ToolDefinition(new ToolName("t"), null, Schema.str(), null,
                (in, ctx) -> new ToolOutcome.Ok(ToolResult.of("ok")), null);
        assertThat(td.description()).isEmpty();
        assertThat(td.options()).isEqualTo(ToolOptions.defaults());
        assertThat(td.outputSchema()).isEqualTo(Option.none());
    }

    @Test
    void withStrict() {
        ToolDefinition td = def("t", Schema.str(), ToolOptions.defaults());
        ToolDefinition strict = td.withStrict(true);
        assertThat(strict).isNotEqualTo(td);
        assertThat(strict.name()).isEqualTo(td.name());
        assertThat(strict.options().strict()).isTrue();
    }

    @Test
    void effectiveNameWithoutNamespace() {
        ToolDefinition td = def("read_file", Schema.str(), ToolOptions.defaults());
        assertThat(td.effectiveName()).isEqualTo("read_file");
    }

    @Test
    void effectiveNameNormalizes() {
        ToolDefinition td = def("read file!", Schema.str(), ToolOptions.defaults());
        assertThat(td.effectiveName()).isEqualTo("read_file_");
    }

    @Test
    void effectiveNameWithNamespace() {
        ToolOptions options = new ToolOptions(Option.some("ns"), Option.none(), true, false);
        ToolDefinition td = def("read", Schema.str(), options);
        assertThat(td.effectiveName()).isEqualTo("ns_read");
    }
}
