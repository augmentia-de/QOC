package de.augmentia.oc.engine.runtime.compaction;

import de.augmentia.oc.engine.core.contract.provider.Choice;
import de.augmentia.oc.engine.core.contract.provider.GenerationOptions;
import de.augmentia.oc.engine.core.contract.provider.ModelContent;
import de.augmentia.oc.engine.core.contract.provider.ModelMessage;
import de.augmentia.oc.engine.core.contract.provider.ModelRequest;
import de.augmentia.oc.engine.core.eval.InstructionEpoch;
import de.augmentia.oc.engine.core.model.CompactionReason;
import de.augmentia.oc.engine.core.model.InboxId;
import de.augmentia.oc.engine.core.model.InstructionEntry;
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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import de.augmentia.oc.engine.core.model.Money;
import de.augmentia.oc.engine.core.model.TokenCount;

/**
 * Full compaction flow: window split, CTX-SUMMARY request, epoch movement and
 * blob storage. Raw events are never rewritten.
 */
public final class CompactionService {

    public static final String CTX_SUMMARY = """
            You MUST summarize the conversation above into a structured summary that will be given to another agent to resume the work.

            Summarize only what the user and the assistant said and did. Leave out instructions and setup the assistant was given rather than told by the user: repository conventions, instruction files such as AGENTS.md, and environment details like the session ID. The next agent receives current versions of all of these separately.

            <template>
            ## Objective
            - [one or two brief sentences describing what the user is trying to accomplish]

            ## Requirements
            - [constraints, preferences, requirements, and scope boundaries stated by the user, or "(none)"]

            ## Decisions
            - [decisions already made and why, or "(none)"]

            ## Work State
            Break the objective into smaller goals and report which are completed, which are being worked on, and which are blocked.
            ### Completed
            - [goals that have been completed; otherwise "(none)"]

            ### Active
            - [goals currently being worked on; otherwise "(none)"]

            ### Blocked
            - [anything blocking progress, and why, or "(none)"]

            ## Next Move
            1. [ordered list of next actions, or "(none)"]

            ## Relevant Files
            List the files and directories, other than the current working directory, that another agent would need to open to continue this work. Include at most 15, most important first. Do not list every file that was read or changed. Include paths outside the current working directory when relevant. If none, write "(none)".
            - `[file or directory path]`: [brief reason it matters]

            ## Important Context
            - [facts the next agent cannot continue without and cannot easily find on its own; or "(none)"]
            </template>

            Rules:
            - Keep each section concise. Use terse, single-line bullets, not prose paragraphs or nested lists.
            - Prefer short references over detailed restatement. It is fine to leave out information the next agent can recover from the code or the files listed above.
            - Preserve exact file paths, symbols, commands, error strings, URLs, and identifiers.
            - Carry forward only user questions or requests that remain unanswered or require further action. Do not repeat ones that newer history has answered or resolved. Preserve exact wording when carrying one forward.
            - Preserve consequential workflow state, including whether changes are uncommitted, committed, pushed, under review, or merged.
            - Do not mention the summary process or that context was compacted.

            Do not continue the task or call tools.
            Return only the structured summary in the requested format. Do not include a preamble, explanation, or other commentary.
            """;

    private final Journal journal;
    private final Conversation conversation;
    private final InstructionStore instructions;
    private final Compaction compaction;
    private final Clock clock;
    private final int keepMessages;

    public CompactionService(Journal journal, Conversation conversation,
                             InstructionStore instructions, Compaction compaction, Clock clock) {
        this(journal, conversation, instructions, compaction, clock, 8);
    }

    public CompactionService(Journal journal, Conversation conversation,
                             InstructionStore instructions, Compaction compaction, Clock clock,
                             int keepMessages) {
        if (journal == null || conversation == null || instructions == null
                || compaction == null || clock == null) {
            throw new IllegalArgumentException("compaction dependencies must not be null");
        }
        this.journal = journal;
        this.conversation = conversation;
        this.instructions = instructions;
        this.compaction = compaction;
        this.clock = clock;
        this.keepMessages = Math.max(1, keepMessages);
    }

    /**
     * Compacts everything except the last {@code keepMessages} messages and
     * advances the instruction epoch to {@code throughSeq}.
     */
    public CompactionResult compact(SessionId session, CompactionReason reason, ModelRef model,
                                    Option<InboxId> inputId, Seq throughSeq, Instant now) {
        if (session == null || reason == null || model == null || throughSeq == null) {
            throw new IllegalArgumentException("compaction inputs must not be null");
        }
        if (inputId == null) inputId = Option.none();
        if (now == null) now = clock.now();

        List<Message> history = conversation.messages(session, Seq.zero());
        CompactionWindow.Window window = CompactionWindow.split(history, keepMessages);

        StringBuilder body = new StringBuilder();
        for (Message message : window.toSummarize()) {
            body.append(message.getClass().getSimpleName()).append(": ")
                    .append(messageText(message)).append('\n');
        }

        ModelRequest request = new ModelRequest(
                model,
                List.of(CTX_SUMMARY),
                List.of(new ModelMessage.User(List.of(new ModelContent.Text(body.toString())))),
                List.of(),
                Option.some(new Choice.None()),
                new GenerationOptions(Option.none(), Option.some(32_000), Option.none(), List.of()),
                Map.of(),
                Map.of(),
                Option.none());

        CompactionContext context = new CompactionContext(
                session, reason, request, inputId, throughSeq, Money.zero(), TokenCount.zero());

        CompactionResult result = compaction.compact(context);
        if (result instanceof CompactionResult.Compacted compacted) {
            advanceEpoch(session, throughSeq, compacted.summary());
        }
        return result;
    }

    private void advanceEpoch(SessionId session, Seq throughSeq, String summary) {
        InstructionState current = instructions.state(session).orElse(null);
        if (current == null) {
            current = InstructionEpoch.start(Seq.zero(), Map.of());
        }
        InstructionState next = InstructionEpoch.start(throughSeq, current.initialValues());
        instructions.putState(session, next);
        instructions.putBlob(summaryHash(summary), summary);
    }

    private static String summaryHash(String summary) {
        return Integer.toHexString(summary.hashCode());
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
