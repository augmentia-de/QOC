package de.augmentia.oc.engine.core;

import de.augmentia.oc.engine.core.eval.TurnPolicy;
import de.augmentia.oc.engine.core.model.TokenCount;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class TurnPolicyTest {

    @Test
    void continuesWhileAllBudgetsHaveHeadroom() {
        TurnPolicy policy = new TurnPolicy(10, 10, 1000, Duration.ofMinutes(5));
        assertThat(policy.decide(1, 1, TokenCount.of(10, 10), Duration.ofSeconds(1)))
                .isEqualTo(TurnPolicy.Decision.CONTINUE);
    }

    @Test
    void answersNowWhenTheNextStepWouldExceedTheBudget() {
        TurnPolicy policy = new TurnPolicy(10, 10, 1000, Duration.ofMinutes(5));
        assertThat(policy.decide(9, 9, TokenCount.of(100, 100), Duration.ofSeconds(1)))
                .isEqualTo(TurnPolicy.Decision.ANSWER_NOW);
    }

    @Test
    void unlimitedStepsStillRespectOtherBudgets() {
        TurnPolicy policy = new TurnPolicy(Integer.MAX_VALUE, 10, 1000, Duration.ofMinutes(5));
        assertThat(policy.decide(100_000, 10, TokenCount.of(100, 100), Duration.ofSeconds(1)))
                .isEqualTo(TurnPolicy.Decision.FAIL_TURN);
    }

    @Test
    void failsTheTurnWhenAHardBudgetIsExceeded() {
        TurnPolicy policy = new TurnPolicy(10, 10, 1000, Duration.ofMinutes(5));
        assertThat(policy.decide(10, 10, TokenCount.of(100, 100), Duration.ofSeconds(1)))
                .isEqualTo(TurnPolicy.Decision.FAIL_TURN);
        assertThat(policy.decide(1, 1, TokenCount.of(600, 500), Duration.ofSeconds(1)))
                .isEqualTo(TurnPolicy.Decision.FAIL_TURN);
        assertThat(policy.decide(1, 1, TokenCount.of(1, 1), Duration.ofMinutes(5)))
                .isEqualTo(TurnPolicy.Decision.FAIL_TURN);
    }
}
