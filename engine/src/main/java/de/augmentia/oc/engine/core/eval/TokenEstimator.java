package de.augmentia.oc.engine.core.eval;

import de.augmentia.oc.engine.core.model.Content;
import de.augmentia.oc.engine.core.model.Message;
import de.augmentia.oc.engine.core.model.ToolCard;

/**
 * Conservative, provider-independent token estimate for compaction decisions.
 * This is intentionally not a tokenizer: it only has to answer whether a
 * context is close to the model limit. Calibrate it with reported usage.
 */
public final class TokenEstimator {

    private static final double CHARS_PER_TOKEN = 3.5;
    private static final int MESSAGE_OVERHEAD = 4;

    private TokenEstimator() {}

    public static int estimate(String text) {
        if (text == null || text.isEmpty()) return 0;
        return Math.max(1, (int) Math.ceil(text.length() / CHARS_PER_TOKEN));
    }

    public static int estimate(Message message) {
        if (message == null) return 0;
        int tokens = MESSAGE_OVERHEAD;
        switch (message) {
            case Message.UserMessage user -> tokens += estimate(user.text());
            case Message.SyntheticMessage synthetic -> tokens += estimate(synthetic.text());
            case Message.SystemMessage system -> tokens += estimate(system.text());
            case Message.SkillMessage skill -> tokens += estimate(skill.text());
            case Message.ShellMessage shell -> tokens += estimate(shell.shell().command());
            case Message.CompactionMessage compaction -> tokens += estimate(compaction.summary());
            case Message.AssistantMessage assistant -> {
                for (Content content : assistant.content()) {
                    if (content instanceof Content.TextContent text) {
                        tokens += estimate(text.text());
                    } else if (content instanceof Content.ToolCardContent card) {
                        tokens += estimateToolCard(card.card());
                    }
                }
            }
            default -> {
                // Agent/model/location switches and idle markers are tiny.
            }
        }
        return tokens;
    }

    private static int estimateToolCard(ToolCard card) {
        int tokens = MESSAGE_OVERHEAD + estimate(card.name().value());
        if (card.input() != null) tokens += estimate(card.input().toString());
        switch (card.state()) {
            case de.augmentia.oc.engine.core.model.ToolState.Completed completed ->
                    tokens += completed.content().stream()
                            .mapToInt(item -> estimate(itemText(item)))
                            .sum();
            case de.augmentia.oc.engine.core.model.ToolState.Failed failed ->
                    tokens += estimate(failed.error().message());
            case de.augmentia.oc.engine.core.model.ToolState.Streaming ignored -> {
                // No result yet.
            }
            case de.augmentia.oc.engine.core.model.ToolState.Running ignored -> {
                // No result yet.
            }
        }
        return tokens;
    }

    private static String itemText(de.augmentia.oc.engine.core.model.ToolContent content) {
        return content instanceof de.augmentia.oc.engine.core.model.ToolContent.TextItem text
                ? text.text() : "";
    }
}
