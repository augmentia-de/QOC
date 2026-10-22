package de.augmentia.oc.engine.runtime;

import de.augmentia.oc.engine.core.eval.InstructionEpoch;
import de.augmentia.oc.engine.core.model.InstructionState;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.Seq;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class InstructionEpochTest {

    @Test
    void startSetsEpochBoundsAndInitialValues() {
        InstructionState state = InstructionEpoch.start(Seq.of(5), Map.of("a", "1"));

        assertThat(state.epochStart()).isEqualTo(Seq.of(5));
        assertThat(state.throughSeq()).isEqualTo(Seq.of(5));
        assertThat(state.initialValues()).containsEntry("a", "1");
        assertThat(state.currentValues()).containsEntry("a", "1");
    }

    @Test
    void applySetsAndRemovesValues() {
        InstructionState state = InstructionEpoch.start(Seq.of(1), Map.of("a", "1", "b", "2"));

        state = InstructionEpoch.apply(state,
                Map.of("a", Option.some("changed"), "b", Option.none()), Seq.of(4));

        assertThat(state.currentValues()).containsEntry("a", "changed");
        assertThat(state.currentValues()).doesNotContainKey("b");
    }

    @Test
    void initialValuesAreNotChangedByDeltas() {
        InstructionState state = InstructionEpoch.start(Seq.of(1), Map.of("a", "1"));
        state = InstructionEpoch.apply(state, Map.of("a", Option.some("2")), Seq.of(2));

        assertThat(state.initialValues()).containsEntry("a", "1");
        assertThat(state.currentValues()).containsEntry("a", "2");
    }

    @Test
    void throughSequenceNeverMovesBackwards() {
        InstructionState state = InstructionEpoch.start(Seq.of(1), Map.of());
        state = InstructionEpoch.apply(state, Map.of("a", Option.some("1")), Seq.of(9));
        state = InstructionEpoch.apply(state, Map.of("a", Option.some("2")), Seq.of(3));

        assertThat(state.throughSeq()).isEqualTo(Seq.of(9));
    }
}
