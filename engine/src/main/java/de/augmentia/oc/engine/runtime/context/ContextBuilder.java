package de.augmentia.oc.engine.runtime.context;

import de.augmentia.oc.engine.core.contract.provider.Choice;
import de.augmentia.oc.engine.core.contract.provider.GenerationOptions;
import de.augmentia.oc.engine.core.contract.provider.ModelContent;
import de.augmentia.oc.engine.core.contract.provider.ModelInfo;
import de.augmentia.oc.engine.core.contract.provider.ModelMessage;
import de.augmentia.oc.engine.core.contract.provider.OutcomePayload;
import de.augmentia.oc.engine.core.contract.tool.ToolDefinition;
import de.augmentia.oc.engine.core.contract.tool.ToolOptions;
import de.augmentia.oc.engine.core.contract.tool.ToolSnapshot;
import de.augmentia.oc.engine.core.eval.PermissionRules;
import de.augmentia.oc.engine.core.model.Effect;
import de.augmentia.oc.engine.core.eval.CacheAffinity;
import de.augmentia.oc.engine.core.eval.PromptLoader;
import de.augmentia.oc.engine.core.eval.OutputBudget;
import de.augmentia.oc.engine.core.eval.PromptLoader;
import de.augmentia.oc.engine.core.model.*;
import de.augmentia.oc.engine.core.port.store.Conversation;
import de.augmentia.oc.engine.core.port.store.InstructionStore;
import de.augmentia.oc.engine.core.port.world.InstructionLoader;
import de.augmentia.oc.engine.runtime.skill.SkillCatalog;
import de.augmentia.oc.engine.runtime.skill.SkillSelection;
import de.augmentia.oc.engine.runtime.skill.SkillFilterOptions;

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

    /** Added when the optional decision model reports insufficient context. */
    static final String ANSWERABILITY_PROMPT =
            "Context check: the available context may be insufficient to answer the user's request. "
            + "Ask a concise clarifying question or gather the missing information before proceeding.";

    /**
     * Default least-privilege permission rules: deny everything, then explicitly
     * allow the implied permissions (edit implies read, write implies read, etc.).
     * Can be used as the {@code defaultPermissions} constructor argument.
     */
    public static List<Rule> leastPrivilegeDefaults() {
        List<Rule> rules = new ArrayList<>();
        // 1. Default: deny everything
        rules.add(new Rule("*", "*", Effect.DENY));
        // 2. Read: allow (read is needed for edit/write/patch to find old text)
        rules.add(new Rule("read", "*", Effect.ALLOW));
        // 3. External directory: deny (no access outside workspace)
        rules.add(new Rule("external_directory", "*", Effect.DENY));
        // 4. Edit/Write/Patch: deny (nothing changes)
        rules.add(new Rule("edit", "*", Effect.DENY));
        rules.add(new Rule("write", "*", Effect.DENY));
        rules.add(new Rule("patch", "*", Effect.DENY));
        // 5. Shell: destructive/unknown commands ask; read-only commands below
        //    are auto-allowed and therefore must come after this rule.
        rules.add(new Rule("shell", "*", Effect.ASK));
        rules.add(new Rule("shell", "*git log*", Effect.ALLOW));
        rules.add(new Rule("shell", "*git status*", Effect.ALLOW));
        rules.add(new Rule("shell", "*git diff*", Effect.ALLOW));
        rules.add(new Rule("shell", "*ls*", Effect.ALLOW));
        rules.add(new Rule("shell", "*cat*", Effect.ALLOW));
        // 6. Secrets: ask (override read for .env)
        rules.add(new Rule("read", "*.env", Effect.ASK));
        rules.add(new Rule("read", "*.env.*", Effect.ASK));
        rules.add(new Rule("read", "*.env.example", Effect.ALLOW));
        return List.copyOf(rules);
    }

    /**
     * Allow-all defaults: everything allowed, with secret protection.
     * OpenCode-compatible (allow-by-default).
     */
    public static List<Rule> allowAllDefaults() {
        List<Rule> rules = new ArrayList<>();
        rules.add(new Rule("*", "*", Effect.ALLOW));
        rules.add(new Rule("read", "*.env", Effect.ASK));
        rules.add(new Rule("read", "*.env.*", Effect.ASK));
        rules.add(new Rule("read", "*.env.example", Effect.ALLOW));
        return List.copyOf(rules);
    }

    /**
     * Returns defaults based on mode: "allow-all" or "least-privilege" (default).
     */
    public static List<Rule> defaults(String mode) {
        if ("allow-all".equalsIgnoreCase(mode)) return allowAllDefaults();
        return leastPrivilegeDefaults();
    }

    private final Conversation conversation;
    private final SkillCatalog skills;
    private final InstructionLoader instructionLoader;
    private final Option<InstructionStore> instructionStore;
    private final SkillFilterOptions skillFilter;
    private final List<Rule> defaultPermissions;
    private volatile de.augmentia.oc.engine.core.port.runtime.DecisionModel decisionModel;
    private volatile de.augmentia.oc.engine.core.port.store.MemoryStore memoryStore;

    public ContextBuilder(Conversation conversation, SkillCatalog skills) {
        this(conversation, skills, InstructionLoader.none(), Option.none(), SkillFilterOptions.defaults());
    }

    public ContextBuilder(Conversation conversation, SkillCatalog skills, InstructionLoader instructionLoader) {
        this(conversation, skills, instructionLoader, Option.none(), SkillFilterOptions.defaults());
    }

    public ContextBuilder(Conversation conversation, SkillCatalog skills, InstructionLoader instructionLoader,
                         Option<InstructionStore> instructionStore) {
        this(conversation, skills, instructionLoader, instructionStore, SkillFilterOptions.defaults());
    }

    public ContextBuilder(Conversation conversation, SkillCatalog skills, InstructionLoader instructionLoader,
                         Option<InstructionStore> instructionStore, SkillFilterOptions skillFilter) {
        this(conversation, skills, instructionLoader, instructionStore, skillFilter, List.of());
    }

    public ContextBuilder(Conversation conversation, SkillCatalog skills, InstructionLoader instructionLoader,
                         Option<InstructionStore> instructionStore, SkillFilterOptions skillFilter,
                         List<Rule> defaultPermissions) {
        if (conversation == null) throw new IllegalArgumentException("conversation must not be null");
        this.conversation = conversation;
        this.skills = skills == null ? SkillCatalog.empty() : skills;
        this.instructionLoader = instructionLoader == null ? InstructionLoader.none() : instructionLoader;
        this.instructionStore = instructionStore == null ? Option.none() : instructionStore;
        this.skillFilter = skillFilter == null ? SkillFilterOptions.defaults() : skillFilter;
        this.defaultPermissions = defaultPermissions == null ? List.of() : List.copyOf(defaultPermissions);
    }

    /** Optional DecisionModel for context-sufficiency checks (fail-open). */
    public void setDecisionModel(de.augmentia.oc.engine.core.port.runtime.DecisionModel dm) {
        this.decisionModel = dm;
    }

    public de.augmentia.oc.engine.core.port.runtime.DecisionModel decisionModel() {
        return decisionModel;
    }

    /** Optional MemoryStore for semantic long-term memory (fail-open). */
    public void setMemoryStore(de.augmentia.oc.engine.core.port.store.MemoryStore ms) {
        this.memoryStore = ms;
    }

    public de.augmentia.oc.engine.core.port.store.MemoryStore memoryStore() {
        return memoryStore;
    }

    /** Build a memory block for the given query text. Returns empty string if disabled. */
    public String memoryBlock(String query) {
        if (memoryStore == null) return "";
        try {
            de.augmentia.oc.engine.core.model.MemoryQuery mq =
                    new de.augmentia.oc.engine.core.model.MemoryQuery(null, null, null, query, 10);
            java.util.List<de.augmentia.oc.engine.core.model.Fact> facts = memoryStore.query(mq);
            if (facts.isEmpty()) return "";
            StringBuilder sb = new StringBuilder();
            sb.append("## Semantic Memory\n");
            for (de.augmentia.oc.engine.core.model.Fact f : facts) {
                sb.append("- ").append(f.entity()).append(" / ").append(f.relation())
                        .append(" = ").append(f.value()).append("\n");
            }
            return sb.toString();
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** Check if the memory store considers the question answerable. Fail-open: true on error. */
    public boolean isMemoryAnswerable(String question) {
        if (memoryStore == null || question == null || question.isBlank()) return true;
        try {
            return memoryStore.checkAnswerable(question).answerable();
        } catch (RuntimeException e) {
            return true;
        }
    }

    /** Returns true if the decision model considers the question answerable given the context. Fail-open: true on error/unavailable. */
    public boolean isAnswerable(String question, String context) {
        if (decisionModel == null || !decisionModel.isAvailable()) return true;
        try {
            de.augmentia.oc.engine.core.model.DecisionResult r = decisionModel.decide(
                    new de.augmentia.oc.engine.core.model.DecisionRequest(
                            "answerable", question == null ? "" : question, java.util.List.of(),
                            context == null ? "" : context, java.util.Map.of()));
            String a = r.answer() == null ? "" : r.answer().trim().toUpperCase(java.util.Locale.ROOT);
            return !(a.equals("NO") || a.equals("FALSE"));
        } catch (RuntimeException e) {
            return true;
        }
    }

    /** Bounded context for the optional answerability check (system + recent messages). */
    private static String answerabilityContext(List<String> system, List<ModelMessage> messages) {
        int max = 4000;
        StringBuilder sb = new StringBuilder();
        for (String block : system) {
            appendBounded(sb, block, max);
            if (sb.length() >= max) break;
        }
        int start = Math.max(0, messages.size() - 6);
        for (int i = start; i < messages.size() && sb.length() < max; i++) {
            ModelMessage m = messages.get(i);
            if (m instanceof ModelMessage.User u) {
                appendContent(sb, u.content(), max);
            } else if (m instanceof ModelMessage.Assistant a) {
                appendContent(sb, a.content(), max);
            }
        }
        return sb.toString();
    }

    private static void appendContent(StringBuilder sb, List<ModelContent> content, int max) {
        for (ModelContent c : content) {
            if (c instanceof ModelContent.Text t) {
                appendBounded(sb, t.text(), max);
                if (sb.length() >= max) return;
            }
        }
    }

    private static void appendBounded(StringBuilder sb, String text, int max) {
        if (text == null) return;
        int remaining = max - sb.length();
        if (remaining <= 0) return;
        sb.append(text, 0, Math.min(text.length(), remaining)).append('\n');
    }

    public Prepared prepare(SessionInfo session, AgentProfile agent, ModelInfo model,
                            ToolSnapshot tools, Seq afterSeq, int step) {
        return prepare(session, agent, model, tools, afterSeq, step, Option.none(), Option.none());
    }

    public Prepared prepare(SessionInfo session, AgentProfile agent, ModelInfo model,
                            ToolSnapshot tools, Seq afterSeq, int step,
                            Option<Choice> forcedChoice) {
        return prepare(session, agent, model, tools, afterSeq, step, forcedChoice, Option.none());
    }

    public Prepared prepare(SessionInfo session, AgentProfile agent, ModelInfo model,
                            ToolSnapshot tools, Seq afterSeq, int step,
                            Option<Choice> forcedChoice, Option<String> query) {
        return prepare(session, agent, model, tools, afterSeq, step, forcedChoice, query, null);
    }

    public Prepared prepare(SessionInfo session, AgentProfile agent, ModelInfo model,
                            ToolSnapshot tools, Seq afterSeq, int step,
                            Option<Choice> forcedChoice, Option<String> query,
                            SkillSelection selection) {
        return prepare(session, agent, model, tools, afterSeq, step, forcedChoice, query, selection,
                Option.none());
    }

    public Prepared prepare(SessionInfo session, AgentProfile agent, ModelInfo model,
                            ToolSnapshot tools, Seq afterSeq, int step,
                            Option<Choice> forcedChoice, Option<String> query,
                            SkillSelection selection, Option<String> promptCacheKey) {
        if (session == null || agent == null || model == null || tools == null) {
            throw new IllegalArgumentException("context inputs must not be null");
        }
        Seq from = afterSeq == null ? Seq.zero() : afterSeq;
        List<Rule> rules = new ArrayList<>();
        rules.addAll(defaultPermissions);
        rules.addAll(agent.permissions());
        rules.addAll(session.permissions());

        // Runtime filtering (Option A): drop tools whose permission resolves to
        // DENY, so the LLM only sees tools it can actually execute. ASK tools
        // are kept (they may still prompt the user in interactive mode).
        ToolSnapshot filteredTools = filterDeniedTools(tools, rules);

        List<String> system = new ArrayList<>();
        String base = agent.system().orElse(basePrompt(filteredTools));
        if (!base.isBlank()) system.add(base);
        for (InstructionLoader.InstructionFile file
                : instructionLoader.load(session.location().directory())) {
            if (file.text().isBlank()) continue;
            if (file.kind() == InstructionLoader.Kind.SYSTEM) {
                system.clear();
            }
            system.add(file.text());
        }
        String instruction = selection != null
                ? skills.instructions(rules, selection)
                : query != null && query.isPresent() && !query.orElseThrow().isBlank()
                        ? skills.instructions(rules, query.orElseThrow(), skillFilter)
                        : skills.instructions(rules);
        if (!instruction.isBlank()) {
            LOG.fine("skills attached: " + instruction.lines().count() + " instruction lines");
            system.add(instruction);
        }
        // Optional memory block (fail-open).
        if (memoryStore != null) {
            String mb = memoryBlock(null);
            if (!mb.isBlank()) system.add(mb);
        }
        // Keep the compaction checkpoint at the end of the stable system
        // blocks instead of rewriting message index 0 on every compaction.
        checkpoint(session.id()).ifPresent(system::add);

        List<ModelMessage> messages = history(session.id(), from, agent);
        LOG.fine("prepare step=" + step + " messages=" + messages.size() + " tools=" + filteredTools.list().size());
        Option<Choice> toolChoice = forcedChoice == null ? Option.none() : forcedChoice;
        if (toolChoice.isEmpty() && agent.maxSteps() > 0 && step >= agent.maxSteps()) {
            messages.add(new ModelMessage.Assistant(List.of(new ModelContent.Text(STEP_LIMIT_PROMPT))));
            toolChoice = Option.some(new Choice.None());
        }

        // Optional context-sufficiency check: only on the first step of a turn,
        // only when a real query is present, and only when the decision model is
        // available (isAnswerable is fail-open). The result is advisory: it adds
        // one system block instead of blocking or rewriting the user's query.
        if (step == 0 && query != null && query.isPresent() && !query.orElseThrow().isBlank()
                && !isAnswerable(query.orElseThrow(), answerabilityContext(system, messages))) {
            system.add(ANSWERABILITY_PROMPT);
        }

        String affinity = (promptCacheKey != null && promptCacheKey.isPresent() && !promptCacheKey.orElseThrow().isBlank())
                ? promptCacheKey.orElseThrow()
                : CacheAffinity.affinity(session.id(), Option.none(), Option.none());
        Map<String, String> headers = new LinkedHashMap<>(CacheAffinity.headers(affinity));
        headers.put("X-Project-Id", session.projectId().value());

        int budget = OutputBudget.compute(model.limits().output(), model.limits().context(),
                Option.none(), 0, false);
        GenerationOptions generation = new GenerationOptions(Option.none(), Option.some(budget),
                Option.none(), List.of());

        ModelRef modelRef = session.model().orElse(new ModelRef(model.providerId(), model.id()));
        return new Prepared(modelRef, system, messages, filteredTools.list(), toolChoice, generation, headers,
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

    /**
     * Runtime permission filtering (Option A): drop tools whose permission
     * resolves to DENY for action=toolName, resource="*". ASK tools are kept so
     * they can still prompt the user in interactive mode.
     */
    private static ToolSnapshot filterDeniedTools(ToolSnapshot tools, List<Rule> rules) {
        if (tools == null || tools.definitions().isEmpty()) return tools == null ? ToolSnapshot.empty() : tools;
        Map<ToolName, ToolDefinition> kept = new LinkedHashMap<>();
        for (Map.Entry<ToolName, ToolDefinition> e : tools.definitions().entrySet()) {
            String action = e.getValue().options().permission().orElse(e.getValue().effectiveName());
            Effect effect = PermissionRules.evaluate(rules, action, "*");
            if (effect != Effect.DENY) {
                kept.put(e.getKey(), e.getValue());
            } else {
                LOG.fine("filtering denied tool: " + action);
            }
        }
        return new ToolSnapshot(kept);
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
