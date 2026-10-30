package de.augmentia.oc.engine.runtime.skill;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SkillFilterOptionsTest {

    @Test
    void defaultsEnableBm25Filtering() {
        SkillFilterOptions options = SkillFilterOptions.defaults();

        assertThat(options.enabled()).isTrue();
        assertThat(options.strategy()).isEqualTo("bm25");
        assertThat(options.topK()).isEqualTo(12);
        assertThat(options.minScore()).isEqualTo(0.25);
        assertThat(options.thresholdCount()).isEqualTo(20);
        assertThat(options.alwaysInclude()).isEmpty();
    }

    @Test
    void validatesLimitsAndCopiesAlwaysInclude() {
        SkillFilterOptions options = new SkillFilterOptions(true, " ", 3, 0.1, 5, List.of("a", "b"));

        assertThat(options.strategy()).isEqualTo("bm25");
        assertThat(options.alwaysInclude()).containsExactly("a", "b");

        assertThatThrownBy(() -> new SkillFilterOptions(true, "bm25", 0, 0.0, 0, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SkillFilterOptions(true, "bm25", 1, -0.1, 0, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SkillFilterOptions(true, "bm25", 1, 0.0, -1, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
