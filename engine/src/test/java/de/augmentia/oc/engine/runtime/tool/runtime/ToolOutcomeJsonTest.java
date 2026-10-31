package de.augmentia.oc.engine.runtime.tool.runtime;

import de.augmentia.oc.engine.core.contract.tool.ToolOutcome;
import de.augmentia.oc.engine.core.contract.tool.ToolResult;
import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.SessionError;
import de.augmentia.oc.engine.core.model.ToolContent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ToolOutcomeJsonTest {

    @Test
    void roundTripsOk() {
        ToolOutcome original = new ToolOutcome.Ok(new ToolResult(Option.none(),
                List.of(new ToolContent.TextItem("hello"), new ToolContent.TextItem("world")), Map.of()));
        JsonValue encoded = ToolOutcomeJson.encode(original);
        ToolOutcome decoded = ToolOutcomeJson.decode(encoded);
        assertThat(decoded).isInstanceOf(ToolOutcome.Ok.class);
        List<ToolContent> content = ((ToolOutcome.Ok) decoded).result().content();
        assertThat(content).hasSize(2);
        assertThat(((ToolContent.TextItem) content.get(0)).text()).isEqualTo("hello");
    }

    @Test
    void roundTripsFailed() {
        ToolOutcome original = new ToolOutcome.Failed(new SessionError.ToolExecutionError("x", "boom"));
        ToolOutcome decoded = ToolOutcomeJson.decode(ToolOutcomeJson.encode(original));
        assertThat(decoded).isInstanceOf(ToolOutcome.Failed.class);
        assertThat(((ToolOutcome.Failed) decoded).error())
                .isInstanceOf(SessionError.ToolExecutionError.class);
    }

    @Test
    void roundTripsPaused() {
        ToolOutcome original = new ToolOutcome.Paused("apr_1", "https://example/approval/apr_1");
        ToolOutcome decoded = ToolOutcomeJson.decode(ToolOutcomeJson.encode(original));
        assertThat(decoded).isInstanceOf(ToolOutcome.Paused.class);
        assertThat(((ToolOutcome.Paused) decoded).approvalId()).isEqualTo("apr_1");
    }

    @Test
    void unknownTypeDecodesToNull() {
        assertThat(ToolOutcomeJson.decode(JsonValue.of(Map.of("type", JsonValue.of("nope"))))).isNull();
        assertThat(ToolOutcomeJson.decode(JsonValue.of("not-an-object"))).isNull();
    }
}