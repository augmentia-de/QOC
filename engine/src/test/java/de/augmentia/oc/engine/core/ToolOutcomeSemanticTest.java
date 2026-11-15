package de.augmentia.oc.engine.core;

import de.augmentia.oc.engine.core.contract.tool.ToolOutcome;
import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.model.SessionError;
import de.augmentia.oc.engine.runtime.tool.runtime.ToolOutcomeJson;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ToolOutcomeSemanticTest {

    @Test
    void empty_isDistinctFromOk() {
        ToolOutcome.Empty empty = new ToolOutcome.Empty("no matches");
        assertThat(empty).isNotInstanceOf(ToolOutcome.Ok.class);
        assertThat(empty.message()).isEqualTo("no matches");
    }

    @Test
    void invalidArgument_isDistinctFromFailed() {
        ToolOutcome.InvalidArgument invalid = new ToolOutcome.InvalidArgument("bad phrase");
        assertThat(invalid).isNotInstanceOf(ToolOutcome.Failed.class);
        assertThat(invalid.message()).isEqualTo("bad phrase");
    }

    @Test
    void unavailable_isDistinctFromFailed() {
        ToolOutcome.Unavailable unavailable = new ToolOutcome.Unavailable("backend down");
        assertThat(unavailable).isNotInstanceOf(ToolOutcome.Failed.class);
        assertThat(unavailable.message()).isEqualTo("backend down");
    }

    @Test
    void failed_stillCarriesSessionError() {
        ToolOutcome.Failed failed = new ToolOutcome.Failed(
                new SessionError.ToolExecutionError("tool_failure", "boom"));
        assertThat(failed.error()).isNotNull();
        assertThat(failed.error().message()).isEqualTo("boom");
    }

    @Test
    void empty_roundTripsThroughJson() {
        ToolOutcome.Empty original = new ToolOutcome.Empty("no matches for query");
        JsonValue encoded = ToolOutcomeJson.encode(original);
        ToolOutcome decoded = ToolOutcomeJson.decode(encoded);
        assertThat(decoded).isInstanceOf(ToolOutcome.Empty.class);
        assertThat(((ToolOutcome.Empty) decoded).message()).isEqualTo("no matches for query");
    }

    @Test
    void invalidArgument_roundTripsThroughJson() {
        ToolOutcome.InvalidArgument original = new ToolOutcome.InvalidArgument("phrase too long");
        JsonValue encoded = ToolOutcomeJson.encode(original);
        ToolOutcome decoded = ToolOutcomeJson.decode(encoded);
        assertThat(decoded).isInstanceOf(ToolOutcome.InvalidArgument.class);
        assertThat(((ToolOutcome.InvalidArgument) decoded).message()).isEqualTo("phrase too long");
    }

    @Test
    void unavailable_roundTripsThroughJson() {
        ToolOutcome.Unavailable original = new ToolOutcome.Unavailable("connection refused");
        JsonValue encoded = ToolOutcomeJson.encode(original);
        ToolOutcome decoded = ToolOutcomeJson.decode(encoded);
        assertThat(decoded).isInstanceOf(ToolOutcome.Unavailable.class);
        assertThat(((ToolOutcome.Unavailable) decoded).message()).isEqualTo("connection refused");
    }

    @Test
    void empty_nullMessage_becomesEmptyString() {
        ToolOutcome.Empty empty = new ToolOutcome.Empty(null);
        assertThat(empty.message()).isEqualTo("");
    }

    @Test
    void invalidArgument_nullMessage_becomesEmptyString() {
        ToolOutcome.InvalidArgument invalid = new ToolOutcome.InvalidArgument(null);
        assertThat(invalid.message()).isEqualTo("");
    }
}