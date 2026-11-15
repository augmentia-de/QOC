package de.augmentia.oc.engine.runtime.context;

import de.augmentia.oc.engine.core.contract.provider.ModelContent;
import de.augmentia.oc.engine.core.contract.provider.ModelMessage;
import de.augmentia.oc.engine.core.contract.provider.OutcomePayload;
import de.augmentia.oc.engine.core.eval.PromptLoader;
import de.augmentia.oc.engine.core.model.Content;
import de.augmentia.oc.engine.core.model.Message;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.ToolContent;
import de.augmentia.oc.engine.core.model.ToolName;
import de.augmentia.oc.engine.core.model.ToolState;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Folds delivered messages into model messages (Layer B projection). Compaction
 * messages are wrapped in the CTX-CHECKPOINT structure hint so the model reads
 * them as historical context, not as instructions.
 */
public final class ConversationFolding {

    private ConversationFolding() {}

    /**
     * Folds a list of delivered messages into model messages. Compaction
     * messages are wrapped in {@code <conversation-checkpoint>}.
     */
    public static List<ModelMessage> fold(List<Message> messages) {
        List<ModelMessage> folded = new ArrayList<>();
        if (messages == null) return folded;
        for (Message message : messages) {
            switch (message) {
                case Message.UserMessage user ->
                        folded.add(new ModelMessage.User(List.of(new ModelContent.Text(user.text()))));
                case Message.SyntheticMessage synthetic ->
                        folded.add(new ModelMessage.User(List.of(new ModelContent.Text(synthetic.text()))));
                case Message.SystemMessage system ->
                        folded.add(new ModelMessage.User(List.of(new ModelContent.Text(system.text()))));
                case Message.SkillMessage skill ->
                        folded.add(new ModelMessage.User(List.of(new ModelContent.Text(skill.text()))));
                case Message.ShellMessage shell ->
                        folded.add(new ModelMessage.User(List.of(new ModelContent.Text(
                                "Shell " + shell.shell().command() + ": " + shell.shell().status()))));
                case Message.AssistantMessage assistant -> foldAssistant(assistant, folded);
                case Message.CompactionMessage compaction -> {
                    String checkpoint = PromptLoader.render("ctx-checkpoint", Map.of(
                            "summary", compaction.summary(),
                            "recent", ""));
                    folded.add(new ModelMessage.User(List.of(new ModelContent.Text(checkpoint))));
                }
                case Message.AgentSwitched switched ->
                        folded.add(new ModelMessage.User(List.of(new ModelContent.Text(
                                "Agent switched to " + switched.agent()))));
                case Message.ModelSwitched switched ->
                        folded.add(new ModelMessage.User(List.of(new ModelContent.Text(
                                "Model switched to " + switched.model()))));
                case Message.LocationSwitched switched ->
                        folded.add(new ModelMessage.User(List.of(new ModelContent.Text(
                                "Location switched to " + switched.location().directory()))));
                case Message.IdleMessage idle ->
                        folded.add(new ModelMessage.User(List.of(new ModelContent.Text(
                                "Idle: " + idle.outcome()))));
            }
        }
        return folded;
    }

    /**
     * Wraps a tool result in delimiters so the model treats it as data, not
     * as instructions (prompt-injection defense).
     */
    public static String wrapToolResult(String toolName, String callId, Object content) {
        String body;
        if (content instanceof de.augmentia.oc.engine.core.model.Content) {
            body = String.valueOf(content);
        } else if (content instanceof de.augmentia.oc.engine.core.contract.provider.ModelContent mc) {
            body = mc.toString();
        } else {
            body = content == null ? "" : String.valueOf(content);
        }
        String safeName = toolName == null ? "tool" : toolName;
        String safeId = callId == null ? "" : callId;
        return "<tool-result name=\"" + safeName + "\" id=\"" + safeId + "\">"
                + body + "</tool-result>";
    }

    private static String toolContentText(Object content) {
        if (content == null) return "";
        if (content instanceof List<?> list) {
            StringBuilder sb = new StringBuilder();
            for (Object c : list) {
                if (c instanceof ToolContent.TextItem t) {
                    if (sb.length() > 0) sb.append("\\n");
                    sb.append(t.text());
                } else if (c instanceof ToolContent.FileItem f) {
                    if (sb.length() > 0) sb.append("\\n");
                    sb.append("[file: ").append(f.uri()).append("]");
                } else {
                    if (sb.length() > 0) sb.append("\\n");
                    sb.append(String.valueOf(c));
                }
            }
            return sb.toString();
        }
        return String.valueOf(content);
    }

    private static void foldAssistant(Message.AssistantMessage assistant, List<ModelMessage> out) {
        List<ModelContent> content = new ArrayList<>();
        List<ModelMessage> results = new ArrayList<>();
        for (Content item : assistant.content()) {
            switch (item) {
                case Content.TextContent text -> content.add(new ModelContent.Text(text.text()));
                case Content.ReasoningContent reasoning -> content.add(new ModelContent.Text(reasoning.text()));
                case Content.ToolCardContent card -> {
                    content.add(new ModelContent.ToolCall(card.card().id(), card.card().name(), card.card().input()));
                    switch (card.card().state()) {
                        case ToolState.Completed completed -> results.add(new ModelMessage.ToolResult(
                                card.card().id(), card.card().name(),
                                new OutcomePayload.Content(
                                        java.util.List.of(new ToolContent.TextItem(
                                                ChatContextReducer.enabled()
                                                        ? ChatContextReducer.reduce(card.card().name().value(),
                                                                card.card().id().value(),
                                                                toolContentText((Object) completed.content()))
                                                        : wrapToolResult(card.card().name().value(),
                                                                card.card().id().value(),
                                                                toolContentText((Object) completed.content())))),
                                        Option.some(completed.metadata()))));
                        case ToolState.Failed failed -> results.add(new ModelMessage.ToolResult(
                                card.card().id(), card.card().name(),
                                new OutcomePayload.Text(wrapToolResult(card.card().name().value(), card.card().id().value(),
                                        failed.error().message()))));
                        default -> {}
                    }
                }
            }
        }
        out.add(new ModelMessage.Assistant(content));
        out.addAll(results);
    }
}
