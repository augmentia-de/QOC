package de.augmentia.oc.engine.runtime.context;

import de.augmentia.oc.engine.core.contract.provider.Choice;
import de.augmentia.oc.engine.core.contract.provider.GenerationOptions;
import de.augmentia.oc.engine.core.contract.provider.ModelContent;
import de.augmentia.oc.engine.core.contract.provider.ModelInfo;
import de.augmentia.oc.engine.core.contract.provider.ModelMessage;
import de.augmentia.oc.engine.core.contract.provider.OutcomePayload;
import de.augmentia.oc.engine.core.contract.tool.ToolDefinition;
import de.augmentia.oc.engine.core.contract.tool.ToolSnapshot;
import de.augmentia.oc.engine.core.eval.CacheAffinity;
import de.augmentia.oc.engine.core.eval.PromptLoader;
import de.augmentia.oc.engine.core.eval.OutputBudget;
import de.augmentia.oc.engine.core.eval.PromptLoader;
import de.augmentia.oc.engine.core.model.*;
import de.augmentia.oc.engine.core.port.store.Conversation;
import de.augmentia.oc.engine.runtime.skill.SkillCatalog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Builds the neutral {@link Prepared} request: two system blocks at most,
 * delivered history only, the frozen tool snapshot, the engine-computed output
 * budget and the session-stable cache affinity.
 */
public final class ContextBuilder {

    public static final String STEP_LIMIT_PROMPT = PromptLoader.get("step-limit");

    private final Conversation conversation;
    private final SkillCatalog skills;

    public ContextBuilder(Conversation conversation, SkillCatalog skills) {
        if (conversation == null) throw new IllegalArgumentException("conversation must not be null");
        this.conversation = conversation;
        this.skills = skills == null ? SkillCatalog.empty() : skills;
    }

    public Prepared prepare(SessionInfo session, AgentProfile agent, ModelInfo model,
                            ToolSnapshot tools, Seq afterSeq, int step) {
        if (session == null || agent == null || model == null || tools == null) {
            throw new IllegalArgumentException("context inputs must not be null");
        }
        Seq from = afterSeq == null ? Seq.zero() : afterSeq;
        List<Rule> rules = Stream.concat(agent.permissions().stream(), session.permissions().stream()).toList();

        List<String> system = new ArrayList<>();
        String base = agent.system().orElse(basePrompt(tools));
        if (!base.isBlank()) system.add(base);
        String instruction = skills.instructions(rules);
        if (!instruction.isBlank()) system.add(instruction);

        List<ModelMessage> messages = history(session.id(), from, agent);
        Option<Choice> toolChoice = Option.none();
        if (agent.maxSteps() > 0 && step >= agent.maxSteps()) {
            messages.add(new ModelMessage.Assistant(List.of(new ModelContent.Text(STEP_LIMIT_PROMPT))));
            toolChoice = Option.some(new Choice.None());
        }

        String affinity = CacheAffinity.affinity(session.id(), Option.none(), Option.none());
        Map<String, String> headers = new LinkedHashMap<>(CacheAffinity.headers(affinity));
        headers.put("X-Project-Id", session.projectId().value());

        int budget = OutputBudget.compute(model.limits().output(), model.limits().context(),
                Option.none(), 0, false);
        GenerationOptions generation = new GenerationOptions(Option.none(), Option.some(budget),
                Option.none(), List.of());

        ModelRef modelRef = session.model().orElse(new ModelRef(model.providerId(), model.id()));
        return new Prepared(modelRef, system, messages, tools.list(), toolChoice, generation, headers,
                Option.some(CacheAffinity.promptCacheKey(affinity)), Map.of());
    }

    private List<ModelMessage> history(SessionId session, Seq afterSeq, AgentProfile agent) {
        List<ModelMessage> messages = new ArrayList<>();
        for (Message message : conversation.messages(session, afterSeq)) {
            append(message, messages);
        }
        // MODE-PLAN: insert reminders as messages (PR-7: logged synthetically).
        boolean hasEnter = messages.stream().anyMatch(m ->
                m instanceof ModelMessage.User u && u.content().stream()
                        .anyMatch(c -> c instanceof ModelContent.Text t
                                && t.text().contains("plan mode")));
        if (agent.planMode() && !hasEnter && !messages.isEmpty()) {
            String reminder = PromptLoader.get("mode-plan-enter");
            int insertAt = Math.max(0, messages.size() - 1);
            messages.add(insertAt, new ModelMessage.User(
                    List.of(new ModelContent.Text(reminder))));
        } else if (!agent.planMode() && hasEnter && !messages.isEmpty()) {
            String reminder = PromptLoader.get("mode-plan-leave");
            int insertAt = Math.max(0, messages.size() - 1);
            messages.add(insertAt, new ModelMessage.User(
                    List.of(new ModelContent.Text(reminder))));
        }
        return messages;
    }

    private void append(Message message, List<ModelMessage> out) {
        switch (message) {
            case Message.UserMessage user ->
                    out.add(new ModelMessage.User(List.of(new ModelContent.Text(user.text()))));
            case Message.SyntheticMessage synthetic ->
                    out.add(new ModelMessage.User(List.of(new ModelContent.Text(synthetic.text()))));
            case Message.SystemMessage system ->
                    out.add(new ModelMessage.User(List.of(new ModelContent.Text(system.text()))));
            case Message.SkillMessage skill ->
                    out.add(new ModelMessage.User(List.of(new ModelContent.Text(skill.text()))));
            case Message.ShellMessage shell ->
                    out.add(new ModelMessage.User(List.of(new ModelContent.Text(
                            "Shell " + shell.shell().command() + ": " + shell.shell().status()))));
            case Message.AssistantMessage assistant -> appendAssistant(assistant, out);
            case Message.CompactionMessage compaction -> {
                String checkpoint = PromptLoader.render("ctx-checkpoint", Map.of(
                        "summary", compaction.summary(),
                        "recent", ""));
                out.add(new ModelMessage.User(List.of(new ModelContent.Text(checkpoint))));
            }
            case Message.AgentSwitched switched ->
                    out.add(new ModelMessage.User(List.of(new ModelContent.Text(
                            "Agent switched to " + switched.agent()))));
            case Message.ModelSwitched switched ->
                    out.add(new ModelMessage.User(List.of(new ModelContent.Text(
                            "Model switched to " + switched.model()))));
            case Message.LocationSwitched switched ->
                    out.add(new ModelMessage.User(List.of(new ModelContent.Text(
                            "Location switched to " + switched.location().directory()))));
            case Message.IdleMessage idle ->
                    out.add(new ModelMessage.User(List.of(new ModelContent.Text(
                            "Idle: " + idle.outcome()))));
        }
    }

    private void appendAssistant(Message.AssistantMessage assistant, List<ModelMessage> out) {
        List<ModelContent> content = new ArrayList<>();
        List<ModelMessage> results = new ArrayList<>();
        for (Content item : assistant.content()) {
            switch (item) {
                case Content.TextContent text -> content.add(new ModelContent.Text(text.text()));
                case Content.ReasoningContent reasoning -> content.add(new ModelContent.Text(reasoning.text()));
                case Content.ToolCardContent card -> {
                    ToolCard toolCard = card.card();
                    content.add(new ModelContent.ToolCall(toolCard.id(), toolCard.name(), toolCard.input()));
                    appendToolResult(toolCard, results);
                }
            }
        }
        out.add(new ModelMessage.Assistant(content));
        out.addAll(results);
    }

    private void appendToolResult(ToolCard card, List<ModelMessage> out) {
        switch (card.state()) {
            case ToolState.Completed completed -> out.add(new ModelMessage.ToolResult(card.id(), card.name(),
                    new OutcomePayload.Content(completed.content(), Option.some(completed.metadata()))));
            case ToolState.Failed failed -> out.add(new ModelMessage.ToolResult(card.id(), card.name(),
                    new OutcomePayload.Text(failed.error().message())));
            default -> {
                // Streaming/running calls have no result yet.
            }
        }
    }

    private static String basePrompt(ToolSnapshot tools) {
        List<String> names = tools.list().stream().map(ToolDefinition::effectiveName).toList();
        StringBuilder base = new StringBuilder(
                PromptLoader.get("base-prompt").replace("${tools}", String.join(", ", names)));
        // SYS-GUIDE: tool-specific hints rendered from the snapshot (PR-5).
        StringBuilder guide = new StringBuilder();
        boolean hasShell = names.stream().anyMatch(n -> n.equals("shell"));
        boolean hasWrite = names.stream().anyMatch(n -> n.equals("write"));
        boolean hasEdit = names.stream().anyMatch(n -> n.equals("edit"));
        String sysGuide = PromptLoader.get("sys-guide");
        // Extract relevant sections based on available tools.
        if (hasShell || hasWrite || hasEdit) {
            base.append("\n\n").append(sysGuide);
        }
        return base.toString();
    }
}
