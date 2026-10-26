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
import de.augmentia.oc.engine.core.port.store.InstructionStore;
import de.augmentia.oc.engine.core.port.world.InstructionLoader;
import de.augmentia.oc.engine.runtime.skill.SkillCatalog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.logging.Logger;

/**
 * Builds the neutral {@link Prepared} request: two system blocks at most,
 * delivered history only, the frozen tool snapshot, the engine-computed output
 * budget and the session-stable cache affinity.
 */
public final class ContextBuilder {
    private static final Logger LOG = Logger.getLogger(ContextBuilder.class.getName());

    public static final String STEP_LIMIT_PROMPT = PromptLoader.get("step-limit");

    private final Conversation conversation;
    private final SkillCatalog skills;
    private final InstructionLoader instructionLoader;
    private final Option<InstructionStore> instructionStore;

    public ContextBuilder(Conversation conversation, SkillCatalog skills) {
        this(conversation, skills, InstructionLoader.none(), Option.none());
    }

    public ContextBuilder(Conversation conversation, SkillCatalog skills, InstructionLoader instructionLoader) {
        this(conversation, skills, instructionLoader, Option.none());
    }

    public ContextBuilder(Conversation conversation, SkillCatalog skills, InstructionLoader instructionLoader,
                         Option<InstructionStore> instructionStore) {
        if (conversation == null) throw new IllegalArgumentException("conversation must not be null");
        this.conversation = conversation;
        this.skills = skills == null ? SkillCatalog.empty() : skills;
        this.instructionLoader = instructionLoader == null ? InstructionLoader.none() : instructionLoader;
        this.instructionStore = instructionStore == null ? Option.none() : instructionStore;
    }

    public Prepared prepare(SessionInfo session, AgentProfile agent, ModelInfo model,
                            ToolSnapshot tools, Seq afterSeq, int step) {
        return prepare(session, agent, model, tools, afterSeq, step, Option.none());
    }

    public Prepared prepare(SessionInfo session, AgentProfile agent, ModelInfo model,
                            ToolSnapshot tools, Seq afterSeq, int step,
                            Option<Choice> forcedChoice) {
        if (session == null || agent == null || model == null || tools == null) {
            throw new IllegalArgumentException("context inputs must not be null");
        }
        Seq from = afterSeq == null ? Seq.zero() : afterSeq;
        List<Rule> rules = Stream.concat(agent.permissions().stream(), session.permissions().stream()).toList();

        List<String> system = new ArrayList<>();
        String base = agent.system().orElse(basePrompt(tools));
        if (!base.isBlank()) system.add(base);
        for (InstructionLoader.InstructionFile file
                : instructionLoader.load(session.location().directory())) {
            if (file.text().isBlank()) continue;
            if (file.kind() == InstructionLoader.Kind.SYSTEM) {
                system.clear();
            }
            system.add(file.text());
        }
        String instruction = skills.instructions(rules);
        if (!instruction.isBlank()) system.add(instruction);
        // Keep the compaction checkpoint at the end of the stable system
        // blocks instead of rewriting message index 0 on every compaction.
        checkpoint(session.id()).ifPresent(system::add);

        List<ModelMessage> messages = history(session.id(), from, agent);
        LOG.fine("prepare step=" + step + " messages=" + messages.size() + " tools=" + tools.list().size());
        Option<Choice> toolChoice = forcedChoice == null ? Option.none() : forcedChoice;
        if (toolChoice.isEmpty() && agent.maxSteps() > 0 && step >= agent.maxSteps()) {
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

    private Option<String> checkpoint(SessionId session) {
        if (instructionStore.isEmpty()) return Option.none();
        InstructionStore store = instructionStore.orElseThrow();
        Option<InstructionState> state = store.state(session);
        if (state.isEmpty()) return Option.none();
        String hash = state.orElseThrow().currentValues().get("compaction.summary");
        if (hash == null || hash.isBlank()) return Option.none();
        Option<String> summary = store.blob(hash);
        if (summary.isEmpty()) return Option.none();
        return Option.some(PromptLoader.render("ctx-checkpoint", Map.of(
                "summary", summary.orElseThrow(), "recent", "")));
    }

    private List<ModelMessage> history(SessionId session, Seq afterSeq, AgentProfile agent) {
        Seq from = afterSeq == null ? Seq.zero() : afterSeq;
        Option<InstructionState> state = Option.none();
        if (instructionStore.isPresent()) {
            state = instructionStore.orElseThrow().state(session);
            if (state.isPresent()) from = state.orElseThrow().epochStart();
        }
        List<ModelMessage> messages = ConversationFolding.fold(conversation.messages(session, from));
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

    private static String basePrompt(ToolSnapshot tools) {
        List<String> names = tools.list().stream().map(ToolDefinition::effectiveName).toList();
        StringBuilder base = new StringBuilder(
                PromptLoader.get("base-prompt").replace("${tools}", String.join(", ", names)));
        // SYS-GUIDE: tool-specific hints rendered from the snapshot (PR-5).
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
