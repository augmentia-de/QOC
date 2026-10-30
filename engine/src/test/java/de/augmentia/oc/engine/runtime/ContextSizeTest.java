package de.augmentia.oc.engine.runtime;

import de.augmentia.oc.engine.core.contract.provider.Choice;
import de.augmentia.oc.engine.core.contract.provider.GenerationOptions;
import de.augmentia.oc.engine.core.contract.provider.ModelContent;
import de.augmentia.oc.engine.core.contract.provider.ModelMessage;
import de.augmentia.oc.engine.core.contract.tool.ToolDefinition;
import de.augmentia.oc.engine.core.contract.tool.ToolOptions;
import de.augmentia.oc.engine.core.json.Schema;
import de.augmentia.oc.engine.core.model.ModelRef;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.ToolName;
import de.augmentia.oc.engine.runtime.context.ContextSize;
import de.augmentia.oc.engine.runtime.context.Prepared;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ContextSizeTest {

    @Test
    void countsSystemMessagesAndTools() {
        Prepared prepared = new Prepared(
                ModelRef.of(de.augmentia.oc.engine.core.model.ProviderId.create(),
                        de.augmentia.oc.engine.core.model.ModelId.create()),
                List.of("system instructions"),
                List.of(new ModelMessage.User(List.of(new ModelContent.Text("hello world")))),
                List.of(new ToolDefinition(new ToolName("read"), "Read a file", Schema.str(),
                        Option.none(), (input, context) -> null, ToolOptions.defaults())),
                Option.none(), GenerationOptions.defaults(), Map.of(), Option.none(), Map.of());

        int tokens = ContextSize.estimate(prepared);

        assertThat(tokens).isGreaterThan(20);
        assertThat(ContextSize.estimate((Prepared) null)).isZero();
    }
}
