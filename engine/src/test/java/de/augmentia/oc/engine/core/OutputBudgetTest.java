package de.augmentia.oc.engine.core;
import de.augmentia.oc.engine.core.eval.OutputBudget;
import de.augmentia.oc.engine.core.model.Option;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutputBudgetTest {

    @Test
    void fallbackWhenProviderReportsNoOutputLimit() {
        assertThat(OutputBudget.compute(0, 0, Option.none(), 0, false))
                .isEqualTo(OutputBudget.FALLBACK_OUTPUT);
    }

    @Test
    void outputCeilingCapsHugeProviderLimits() {
        assertThat(OutputBudget.compute(1_000_000, 0, Option.none(), 0, false))
                .isEqualTo(OutputBudget.OUTPUT_CEILING);
    }

    @Test
    void compactionIsCappedAtThirtyTwoThousand() {
        assertThat(OutputBudget.compute(200_000, 0, Option.none(), 0, true))
                .isEqualTo(OutputBudget.COMPACTION_OUTPUT);
    }

    @Test
    void withoutContextTheDesiredBudgetIsReturned() {
        assertThat(OutputBudget.compute(8_000, 0, Option.some(1_000L), 500, false))
                .isEqualTo(8_000);
        assertThat(OutputBudget.compute(8_000, 10_000, Option.none(), 0, false))
                .isEqualTo(8_000);
    }

    @Test
    void freeSpaceLimitsTheBudget() {
        // context 10_000 - measured 9_000 = 1_000 -> floor is 1_024
        assertThat(OutputBudget.compute(8_000, 10_000, Option.some(9_000L), 0, false))
                .isEqualTo(OutputBudget.MIN_OUTPUT);
    }

    @Test
    void estimateAddsFifteenPercentRoundedUp() {
        // 10_000 * 1.15 = 11_500; room = 100_000 - 50_000 - 11_500 = 38_500
        assertThat(OutputBudget.compute(100_000, 100_000, Option.some(50_000L), 10_000, false))
                .isEqualTo(38_500);
    }

    @Test
    void measuredOnlyDoesNotAddTheMargin() {
        // room = 100_000 - 50_000 = 50_000
        assertThat(OutputBudget.compute(100_000, 100_000, Option.some(50_000L), 0, false))
                .isEqualTo(50_000);
    }

    @Test
    void estimatedOnlyIsUsedWithTheMargin() {
        // room = 100_000 - ceil(10_000*1.15) = 88_500
        assertThat(OutputBudget.compute(100_000, 100_000, Option.none(), 10_000, false))
                .isEqualTo(88_500);
    }

    @Test
    void desiredWinsWhenThereIsEnoughRoom() {
        assertThat(OutputBudget.compute(8_000, 200_000, Option.some(1_000L), 1_000, false))
                .isEqualTo(8_000);
    }

    @Test
    void overflowRoomNeverDropsBelowMinimum() {
        assertThat(OutputBudget.compute(8_000, 1_000, Option.some(5_000L), 5_000, false))
                .isEqualTo(OutputBudget.MIN_OUTPUT);
    }

    @Test
    void negativeEstimateIsRejected() {
        assertThatThrownBy(() -> OutputBudget.compute(1_000, 1_000, Option.none(), -1, false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void measuredInputConvenienceOverload() {
        assertThat(OutputBudget.compute(8_000, 100_000, 1_000L, 0, false)).isEqualTo(8_000);
    }
}
