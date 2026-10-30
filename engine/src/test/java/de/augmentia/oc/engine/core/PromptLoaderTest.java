package de.augmentia.oc.engine.core;

import de.augmentia.oc.engine.core.eval.PromptLoader;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PromptLoaderTest {

    @Test
    void loadsKnownPrompt() {
        String text = PromptLoader.get("step-limit");
        assertThat(text).startsWith("CRITICAL - MAXIMUM STEPS REACHED");
        assertThat(text).contains("Tools are disabled until next user input");
        assertThat(text).contains("Respond with text ONLY");
    }

    @Test
    void cachesTheResult() {
        assertThat(PromptLoader.get("no-skills")).isSameAs(PromptLoader.get("no-skills"));
    }

    @Test
    void rendersPlaceholders() {
        String rendered = PromptLoader.render("bounding-hint", java.util.Map.of(
                "shown", "5", "total", "10", "path", "/tmp/out"));
        assertThat(rendered).isEqualTo("[showing lines 1-5 of 10; full output saved to /tmp/out]");
    }

    @Test
    void missingPromptIsAnError() {
        assertThatThrownBy(() -> PromptLoader.get("does-not-exist"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blankIdIsRejected() {
        assertThatThrownBy(() -> PromptLoader.get(" "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PromptLoader.get(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
