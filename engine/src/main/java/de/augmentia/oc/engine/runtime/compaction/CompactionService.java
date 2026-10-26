package de.augmentia.oc.engine.runtime.compaction;

import de.augmentia.oc.engine.core.contract.provider.Choice;
import de.augmentia.oc.engine.core.contract.provider.GenerationOptions;
import de.augmentia.oc.engine.core.contract.provider.ModelContent;
import de.augmentia.oc.engine.core.contract.provider.ModelMessage;
import de.augmentia.oc.engine.core.contract.provider.ModelEvent;
import de.augmentia.oc.engine.core.contract.provider.ModelRequest;
import de.augmentia.oc.engine.core.contract.provider.ProviderAdapter;
import de.augmentia.oc.engine.core.eval.CacheAffinity;
import de.augmentia.oc.engine.core.eval.PromptLoader;
import de.augmentia.oc.engine.core.model.CompactionReason;
import de.augmentia.oc.engine.core.model.InboxId;
import de.augmentia.oc.engine.core.model.InstructionState;
import de.augmentia.oc.engine.core.model.Content;
import de.augmentia.oc.engine.core.model.Message;
import de.augmentia.oc.engine.core.model.ModelRef;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.Seq;
import de.augmentia.oc.engine.core.model.SessionId;
import de.augmentia.oc.engine.core.port.runtime.Clock;
import de.augmentia.oc.engine.core.port.store.Conversation;
import de.augmentia.oc.engine.core.port.store.InstructionStore;
import de.augmentia.oc.engine.core.port.store.Journal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import de.augmentia.oc.engine.core.model.Money;
import de.augmentia.oc.engine.core.model.TokenCount;

/**
 * Full compaction flow: window split, CTX-SUMMARY request, epoch movement and
 * blob storage. Raw events are never rewritten.
 */
public final class CompactionService {

    public static final String CTX_SUMMARY = PromptLoader.get("ctx-summary");
    public static final String CTX_SUMMARY_UPDATE = PromptLoader.get("ctx-summary-update");
    public static final String SUMMARY_KEY = "compaction.summary";

    private final Conversation conversation;
    private final InstructionStore instructions;
    private final Compaction compaction;
    private final Clock clock;
    private final ProviderAdapter provider;
    private final CompactionPolicy policy;

    public CompactionService(Journal journal, Conversation conversation,
                             InstructionStore instructions, Compaction compaction, Clock clock) {
        this(journal, conversation, instructions, compaction, clock, CompactionPolicy.DEFAULT, null);
    }

    public CompactionService(Journal journal, Conversation conversation,
                             InstructionStore instructions, Compaction compaction, Clock clock,
                             ProviderAdapter provider) {
        this(journal, conversation, instructions, compaction, clock, CompactionPolicy.DEFAULT, provider);
    }

    public CompactionService(Journal journal, Conversation conversation,
                             InstructionStore instructions, Compaction compaction, Clock clock,
                             int keepMessages, ProviderAdapter provider) {
        this(journal, conversation, instructions, compaction, clock,
                new CompactionPolicy(keepMessages, 0, 0), provider);
    }

    public CompactionService(Journal journal, Conversation conversation,
                             InstructionStore instructions, Compaction compaction, Clock clock,
                             CompactionPolicy policy, ProviderAdapter provider) {
        if (journal == null || conversation == null || instructions == null
                || compaction == null || clock == null) {
            throw new IllegalArgumentException("compaction dependencies must not be null");
        }
        this.conversation = conversation;
        this.instructions = instructions;
        this.compaction = compaction;
        this.clock = clock;
        this.policy = policy == null ? CompactionPolicy.DEFAULT : policy;
        this.provider = provider;
    }

    /** True when at least one message would be summarized by a manual pass. */
    public boolean hasWork(SessionId session) {
        if (session == null) throw new IllegalArgumentException("session must not be null");
        return !CompactionWindow.split(conversation.messages(session, Seq.zero()), policy, 0)
                .toSummarize().isEmpty();
    }

    /**
     * Compacts everything except the recent tail and advances the instruction
     * epoch to {@code throughSeq}. This overload keeps the legacy
     * message-count behavior; the engine uses the context-aware overload.
     */
    public CompactionResult compact(SessionId session, CompactionReason reason, ModelRef model,
                                    Option<InboxId> inputId, Seq throughSeq, Instant now) {
        return compact(session, reason, model, 0, inputId, throughSeq, now);
    }

    /**
     * Compacts with the model's context window so the recent tail is chosen by
     * tokens, not only by message count.
     */
    public CompactionResult compact(SessionId session, CompactionReason reason, ModelRef model,
                                    int contextWindow, Option<InboxId> inputId, Seq throughSeq,
                                    Instant now) {
        if (session == null || reason == null || model == null || throughSeq == null) {
            throw new IllegalArgumentException("compaction inputs must not be null");
        }
        if (inputId == null) inputId = Option.none();
        if (now == null) now = clock.now();

        List<Message> history = conversation.messages(session, Seq.zero());
        CompactionWindow.Window window = CompactionWindow.split(history, policy, contextWindow);

        StringBuilder body = new StringBuilder();
        for (Message message : window.toSummarize()) {
            body.append(message.getClass().getSimpleName()).append(": ")
                    .append(messageText(message)).append('\n');
        }

        // CTX-SUMMARY-UPDATE when a previous compaction exists (PR-8).
        InstructionState previous = previousState(session);
        if (previous != null) {
            String key = previous.currentValues().get(SUMMARY_KEY);
            if (key == null || key.isBlank()) {
                return new CompactionResult.PredecessorMissing(
                        "previous compaction has no summary reference");
            }
            Option<String> stored = instructions.blob(key);
            if (stored.isEmpty()) {
                return new CompactionResult.PredecessorMissing(
                        "previous summary blob is missing: " + key);
            }
            body.insert(0, "Previous summary (update it):\n" + stored.orElseThrow() + "\n\n");
        }
        String prompt = previous != null ? CTX_SUMMARY_UPDATE : CTX_SUMMARY;

        Map<String, String> headers = CacheAffinity.headers(
                CacheAffinity.affinity(session, Option.none(), Option.none()));
        ModelRequest request = new ModelRequest(
                model,
                List.of(prompt),
                List.of(new ModelMessage.User(List.of(new ModelContent.Text(body.toString())))),
                List.of(),
                Option.some(new Choice.None()),
                new GenerationOptions(Option.none(), Option.some(32_000), Option.none(), List.of()),
                Map.of(),
                headers,
                Option.none());

        CompactionContext context = new CompactionContext(
                session, reason, request, inputId, throughSeq, Money.zero(), TokenCount.zero());

        CompactionResult result = compaction.compact(context);
        if (result instanceof CompactionResult.Compacted compacted) {
            String summary = compacted.summary();
            // CTX-NUDGE: if the response did not follow the template, re-prompt once.
            if (!CtxNudge.followsTemplate(summary) && provider != null) {
                String nudge = CtxNudge.prompt();
                List<ModelEvent> nudgeEvents = provider.stream(new ModelRequest(
                        context.request().model(),
                        List.of(nudge),
                        List.of(new ModelMessage.User(List.of(new ModelContent.Text(body.toString())))),
                        List.of(),
                        Option.some(new Choice.None()),
                        new GenerationOptions(Option.none(), Option.some(32_000), Option.none(), List.of()),
                        Map.of(), headers, Option.none())).toList();
                StringBuilder nudgeSummary = new StringBuilder();
                for (ModelEvent event : nudgeEvents) {
                    if (event instanceof ModelEvent.TextEnded ended) nudgeSummary.append(ended.text());
                    else if (event instanceof ModelEvent.TextDelta delta) nudgeSummary.append(delta.text());
                }
                if (!nudgeSummary.toString().isBlank()) summary = nudgeSummary.toString();
            }
            advanceEpoch(session, throughSeq, summary);
            // Update the result's summary by re-wrapping
            result = new CompactionResult.Compacted(summary, compacted.epochStart());
        }
        return result;
    }

    private InstructionState previousState(SessionId session) {
        InstructionState state = instructions.state(session).orElse(null);
        return state != null && state.epochStart().value() > 0 ? state : null;
    }

    private void advanceEpoch(SessionId session, Seq throughSeq, String summary) {
        InstructionState current = instructions.state(session).orElse(null);
        Map<String, String> initialValues = current == null ? Map.of() : current.initialValues();
        Map<String, String> currentValues = new LinkedHashMap<>(initialValues);
        String key = summaryKey(summary);
        currentValues.put(SUMMARY_KEY, key);
        InstructionState next = new InstructionState(throughSeq, throughSeq, initialValues, currentValues);
        // Store the text first: a state that points at a missing blob is worse
        // than a blob that is not referenced yet.
        instructions.putBlob(key, summary);
        instructions.putState(session, next);
    }

    /** Collision-safe content address for compaction summaries. */
    static String summaryKey(String summary) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(summary.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JVM", e);
        }
    }

    private static String messageText(Message message) {
        return switch (message) {
            case Message.UserMessage user -> user.text();
            case Message.SyntheticMessage synthetic -> synthetic.text();
            case Message.SystemMessage system -> system.text();
            case Message.SkillMessage skill -> skill.text();
            case Message.ShellMessage shell -> shell.shell().command();
            case Message.AssistantMessage assistant -> assistant.content().stream()
                    .filter(item -> item instanceof Content.TextContent)
                    .map(item -> ((Content.TextContent) item).text())
                    .reduce("", String::concat);
            case Message.CompactionMessage compaction -> compaction.summary();
            case Message.AgentSwitched switched -> "agent switched to " + switched.agent();
            case Message.ModelSwitched switched -> "model switched to " + switched.model();
            case Message.LocationSwitched switched -> "location switched";
            case Message.IdleMessage idle -> "idle: " + idle.outcome();
        };
    }
}
