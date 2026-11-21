package de.augmentia.oc.engine.runtime;

import de.augmentia.oc.engine.adapter.process.JdkProcessRunner;
import de.augmentia.oc.engine.core.contract.tool.Hooks;
import de.augmentia.oc.engine.core.contract.tool.ToolCall;
import de.augmentia.oc.engine.core.contract.tool.ToolContext;
import de.augmentia.oc.engine.core.contract.tool.ToolDefinition;
import de.augmentia.oc.engine.core.contract.tool.ToolOutcome;
import de.augmentia.oc.engine.core.contract.tool.ToolResult;
import de.augmentia.oc.engine.core.contract.tool.ToolSnapshot;
import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.model.AgentId;
import de.augmentia.oc.engine.core.model.AssistantTime;
import de.augmentia.oc.engine.core.model.CallId;
import de.augmentia.oc.engine.core.model.Content;
import de.augmentia.oc.engine.core.model.Effect;
import de.augmentia.oc.engine.core.model.Message;
import de.augmentia.oc.engine.core.model.MessageId;
import de.augmentia.oc.engine.core.model.ModelId;
import de.augmentia.oc.engine.core.model.ModelRef;
import de.augmentia.oc.engine.core.model.Money;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.ProviderId;
import de.augmentia.oc.engine.core.model.Rule;
import de.augmentia.oc.engine.core.model.Seq;
import de.augmentia.oc.engine.core.model.SessionEvent;
import de.augmentia.oc.engine.core.model.SessionId;
import de.augmentia.oc.engine.core.model.TokenCount;
import de.augmentia.oc.engine.core.model.ToolCard;
import de.augmentia.oc.engine.core.model.ToolName;
import de.augmentia.oc.engine.core.model.ToolState;
import de.augmentia.oc.engine.runtime.tool.meta.WorktreeTool;
import de.augmentia.oc.engine.runtime.tool.registry.ToolRegistry;
import de.augmentia.oc.engine.runtime.tool.runtime.ToolAuthorizer;
import de.augmentia.oc.engine.runtime.tool.runtime.ToolRuntime;
import de.augmentia.oc.engine.runtime.tool.undo.UndoRegistry;
import de.augmentia.oc.engine.runtime.tool.undo.UndoRuntime;
import de.augmentia.oc.engine.runtime.tool.undo.UndoToolFactory;
import de.augmentia.oc.engine.testing.InMemoryConversation;
import de.augmentia.oc.engine.testing.InMemoryJournal;
import de.augmentia.oc.engine.testing.InMemoryToolOutput;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class WorktreeUndoIntegrationTest {

    @Test
    void worktreeCreateIsUndoneByItsCompensatingTool(@TempDir Path workspace) throws Exception {
        Assumptions.assumeTrue(gitAvailable(), "git is not installed");
        run(workspace, "git", "init", "-q");
        run(workspace, "git", "config", "user.email", "test@example.com");
        run(workspace, "git", "config", "user.name", "Test");
        Files.writeString(workspace.resolve("a.txt"), "a");
        run(workspace, "git", "add", ".");
        run(workspace, "git", "commit", "-qm", "init");

        JdkProcessRunner processRunner = new JdkProcessRunner();
        Assumptions.assumeTrue(WorktreeTool.available(processRunner, workspace),
                "worktree tools require a git working tree");

        WorktreeTool worktrees = new WorktreeTool(processRunner, workspace);
        ToolRegistry registry = new ToolRegistry();
        registry.register(WorktreeTool.create(worktrees));
        registry.register(WorktreeTool.remove(worktrees));

        UndoRegistry undoRegistry = new UndoRegistry();
        undoRegistry.rebuildFrom(registry);

        InMemoryConversation conversation = new InMemoryConversation();
        InMemoryJournal journal = new InMemoryJournal();
        AtomicReference<ToolRuntime> runtimeRef = new AtomicReference<>();
        UndoRuntime undoRuntime = new UndoRuntime(conversation, journal, registry, undoRegistry,
                runtimeRef::get, null);

        for (ToolDefinition forward : new ArrayList<>(registry.list())) {
            forward.options().undo().ifPresent(spec ->
                    registry.register(UndoToolFactory.create(forward, spec, undoRuntime)));
        }

        ToolSnapshot snapshot = registry.snapshot(List.of(new Rule("*", "*", Effect.ALLOW)));
        ToolRuntime toolRuntime = new ToolRuntime(snapshot, ToolAuthorizer.defaultAuthorizer(),
                request -> {}, noopHooks(), new InMemoryToolOutput(), null, 120);
        runtimeRef.set(toolRuntime);

        SessionId session = SessionId.create();
        AgentId agent = AgentId.create();
        MessageId message = MessageId.create();

        JsonValue createInput = JsonValue.of(Map.of("name", JsonValue.of("feature")));
        CallId createCall = CallId.create();
        ToolOutcome createOutcome = toolRuntime.execute(new ToolCall(createCall,
                new ToolName("worktree_create"), createInput),
                new ToolContext(session, agent, message, createCall));

        assertThat(createOutcome).isInstanceOf(ToolOutcome.Ok.class);
        ToolOutcome.Ok ok = (ToolOutcome.Ok) createOutcome;
        Path created = workspace.resolve(WorktreeTool.DIRECTORY).resolve("feature");
        assertThat(created).exists();

        conversation.add(session, Seq.of(1), assistantMessage(agent, createCall, createInput, ok));

        CallId undoCall = CallId.create();
        ToolOutcome undoOutcome = toolRuntime.execute(new ToolCall(undoCall,
                new ToolName("undo_worktree_create"),
                JsonValue.of(Map.of("callId", JsonValue.of(createCall.value())))),
                new ToolContext(session, agent, message, undoCall));

        assertThat(undoOutcome).isInstanceOf(ToolOutcome.Ok.class);
        assertThat(created).doesNotExist();
        assertThat(journal.history(session.value(), Seq.zero()))
                .anyMatch(event -> event.payload() instanceof SessionEvent.ToolSucceeded succeeded
                        && createCall.value().equals(succeeded.metadata().get("undoOf")));
    }

    private static Message.AssistantMessage assistantMessage(AgentId agent, CallId callId,
                                                             JsonValue input, ToolOutcome.Ok ok) {
        ToolCard card = new ToolCard(callId, new ToolName("worktree_create"), input,
                new ToolState.Completed(input, ok.result().content(), ok.result().metadata()),
                true, Instant.now());
        return new Message.AssistantMessage(MessageId.create(), agent,
                new ModelRef(ProviderId.create(), ModelId.create()),
                List.of(new Content.ToolCardContent(card)),
                Option.none(), Option.none(), Option.none(), Option.none(),
                Money.zero(), TokenCount.zero(), AssistantTime.started(Instant.now()));
    }

    private static Hooks noopHooks() {
        return new Hooks() {
            @Override
            public ToolCall beforeToolCall(ToolContext context, ToolCall call) {
                return call;
            }

            @Override
            public ToolResult afterToolCall(ToolContext context, ToolResult result) {
                return result;
            }
        };
    }

    private static boolean gitAvailable() {
        try {
            return new ProcessBuilder("git", "--version").start().waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static void run(Path directory, String... command) throws Exception {
        Process process = new ProcessBuilder(command)
                .directory(directory.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes());
        if (process.waitFor() != 0) {
            throw new IllegalStateException(String.join(" ", command) + ": " + output);
        }
    }
}
