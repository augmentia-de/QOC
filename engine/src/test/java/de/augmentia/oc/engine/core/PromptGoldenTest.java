package de.augmentia.oc.engine.core;

import de.augmentia.oc.engine.core.eval.PromptLoader;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Golden tests for prompt texts (PR-10): a change to any prompt is a
 * behavior change and must be visible. These tests pin the exact text.
 */
class PromptGoldenTest {

    @Test
    void stepLimit() {
        assertThat(PromptLoader.get("step-limit"))
                .isEqualTo("The step limit is reached. Summarize what was done and what remains.");
    }

    @Test
    void serverRestarted() {
        assertThat(PromptLoader.get("server-restarted"))
                .isEqualTo("The server was restarted. Continuing.");
    }

    @Test
    void ctxNudge() {
        assertThat(PromptLoader.get("ctx-nudge"))
                .isEqualTo("The previous response did not fill in the required summary template. Do not call tools. Return the summary as text using the exact section headings from the template.");
    }

    @Test
    void basePromptRendersTools() {
        String rendered = PromptLoader.get("base-prompt").replace("${tools}", "read, write");
        assertThat(rendered).contains("You are the OpenCode session engine.");
        assertThat(rendered).contains("Available tools: read, write");
    }

    @Test
    void boundingHintRendersPlaceholders() {
        String rendered = PromptLoader.render("bounding-hint", Map.of(
                "shown", "5", "total", "10", "path", "/tmp/out"));
        assertThat(rendered).isEqualTo("[showing lines 1-5 of 10; full output saved to /tmp/out]");
    }

    @Test
    void unableToLoadSkillRendersId() {
        String rendered = PromptLoader.render("unable-to-load-skill", Map.of("id", "commit"));
        assertThat(rendered).isEqualTo("Unable to load skill commit");
    }

    @Test
    void ctxCheckpointWrapsSummaryAndRecent() {
        String rendered = PromptLoader.render("ctx-checkpoint", Map.of(
                "summary", "S", "recent", "R"));
        assertThat(rendered).startsWith("<conversation-checkpoint>");
        assertThat(rendered).contains("<summary>\nS\n</summary>");
        assertThat(rendered).contains("<recent-context>\nR\n</recent-context>");
        assertThat(rendered).endsWith("</conversation-checkpoint>");
    }

    @Test
    void mcpInstructionsRendersServers() {
        String rendered = PromptLoader.render("mcp-instructions", Map.of("servers", "  <server name=\"x\">\n    <tool>t</tool>\n  </server>"));
        assertThat(rendered).startsWith("<mcp_instructions>");
        assertThat(rendered).contains("<server name=\"x\">");
        assertThat(rendered).endsWith("</mcp_instructions>");
    }

    @Test
    void ctxSummaryContainsTemplate() {
        assertThat(PromptLoader.get("ctx-summary")).contains("<template>");
        assertThat(PromptLoader.get("ctx-summary")).contains("## Objective");
        assertThat(PromptLoader.get("ctx-summary")).contains("## Next Move");
    }

    @Test
    void ctxSummaryUpdateContainsConsolidationRules() {
        assertThat(PromptLoader.get("ctx-summary-update")).contains("consolidated summary");
        assertThat(PromptLoader.get("ctx-summary-update")).contains("Newer history");
    }

    @Test
    void modePlanEnterAndLeave() {
        assertThat(PromptLoader.get("mode-plan-enter")).contains("plan mode");
        assertThat(PromptLoader.get("mode-plan-leave")).contains("leaving plan mode");
    }

    @Test
    void ttlMessageAndConversation() {
        assertThat(PromptLoader.get("ttl-message")).contains("<message>");
        assertThat(PromptLoader.get("ttl-conversation")).contains("<conversation>");
    }

    @Test
    void skillGuidance() {
        assertThat(PromptLoader.get("skill-guidance")).contains("Skills provide");
        assertThat(PromptLoader.get("skill-guidance")).contains("skill tool");
    }

    @Test
    void noSkills() {
        assertThat(PromptLoader.get("no-skills"))
                .isEqualTo("No skills are currently available.");
    }
}
