package de.augmentia.oc.engine.runtime.tool.fs;

import de.augmentia.oc.engine.adapter.fs.NioFileSystem;
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
import de.augmentia.oc.engine.core.model.SessionId;
import de.augmentia.oc.engine.core.model.TokenCount;
import de.augmentia.oc.engine.core.model.ToolCard;
import de.augmentia.oc.engine.core.model.ToolName;
import de.augmentia.oc.engine.core.model.ToolState;
import de.augmentia.oc.engine.core.port.world.FileSystem;
import de.augmentia.oc.engine.runtime.tool.registry.ToolRegistry;
import de.augmentia.oc.engine.runtime.tool.runtime.ToolAuthorizer;
import de.augmentia.oc.engine.runtime.tool.runtime.ToolRuntime;
import de.augmentia.oc.engine.runtime.tool.undo.UndoRegistry;
import de.augmentia.oc.engine.runtime.tool.undo.UndoRuntime;
import de.augmentia.oc.engine.runtime.tool.undo.UndoToolFactory;
import de.augmentia.oc.engine.testing.InMemoryConversation;
import de.augmentia.oc.engine.testing.InMemoryJournal;
import de.augmentia.oc.engine.testing.InMemoryToolOutput;
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

class WriteUndoIntegrationTest {

    @Test
    void writeOfANewFileIsUndoneByDelete(@TempDir Path workspace) {
        Fixture fixture = new Fixture(workspace);
        Path file = workspace.resolve("new.txt");

        CallId writeCall = fixture.write("new.txt", "hello");
        assertThat(file).exists();

        ToolOutcome undo = fixture.undo(writeCall);

        assertThat(undo).isInstanceOf(ToolOutcome.Ok.class);
        assertThat(file).doesNotExist();
    }

    @Test
    void overwriteIsRejectedByTheGuard(@TempDir Path workspace) throws Exception {
        Path file = workspace.resolve("existing.txt");
        Files.writeString(file, "old");
        Fixture fixture = new Fixture(workspace);

        CallId writeCall = fixture.write("existing.txt", "new");
        assertThat(Files.readString(file)).isEqualTo("new");

        ToolOutcome undo = fixture.undo(writeCall);

        assertThat(undo).isInstanceOf(ToolOutcome.Failed.class);
        assertThat(((ToolOutcome.Failed) undo).error().message()).contains("guard");
        assertThat(Files.readString(file)).isEqualTo("new");
    }

    private static final class Fixture {
        private final SessionId session = SessionId.create();
        private final AgentId agent = AgentId.create();
        private final MessageId message = MessageId.create();
        private final InMemoryConversation conversation = new InMemoryConversation();
        private final ToolRuntime runtime;

        Fixture(Path workspace) {
            FileSystem fs = new NioFileSystem(workspace);
            FileAccess access = new FileAccess(fs, workspace, workspace);
            ToolRegistry registry = new ToolRegistry();
            registry.register(WriteTool.definition(fs, access));
            registry.register(DeleteTool.definition(fs, access));

            UndoRegistry undoRegistry = new UndoRegistry();
            undoRegistry.rebuildFrom(registry);

            InMemoryJournal journal = new InMemoryJournal();
            AtomicReference<ToolRuntime> runtimeRef = new AtomicReference<>();
            UndoRuntime undoRuntime = new UndoRuntime(conversation, journal, registry, undoRegistry,
                    runtimeRef::get, null);

            for (ToolDefinition forward : new ArrayList<>(registry.list())) {
                forward.options().undo().ifPresent(spec ->
                        registry.register(UndoToolFactory.create(forward, spec, undoRuntime)));
            }

            ToolSnapshot snapshot = registry.snapshot(List.of(new Rule("*", "*", Effect.ALLOW)));
            runtime = new ToolRuntime(snapshot, ToolAuthorizer.defaultAuthorizer(),
                    request -> {}, noopHooks(), new InMemoryToolOutput(), null, 30);
            runtimeRef.set(runtime);
        }

        CallId write(String path, String content) {
            JsonValue input = JsonValue.of(Map.of(
                    "path", JsonValue.of(path),
                    "content", JsonValue.of(content)));
            CallId callId = CallId.create();
            ToolOutcome outcome = runtime.execute(new ToolCall(callId, new ToolName("write"), input),
                    new ToolContext(session, agent, message, callId));
            assertThat(outcome).isInstanceOf(ToolOutcome.Ok.class);
            ToolOutcome.Ok ok = (ToolOutcome.Ok) outcome;
            conversation.add(session, Seq.of(1), assistantMessage(agent, callId,
                    new ToolName("write"), input, ok));
            return callId;
        }

        ToolOutcome undo(CallId writeCall) {
            CallId undoCall = CallId.create();
            return runtime.execute(new ToolCall(undoCall, new ToolName("undo_write"),
                            JsonValue.of(Map.of("callId", JsonValue.of(writeCall.value())))),
                    new ToolContext(session, agent, message, undoCall));
        }
    }

    private static Message.AssistantMessage assistantMessage(AgentId agent, CallId callId,
                                                             ToolName tool, JsonValue input,
                                                             ToolOutcome.Ok ok) {
        ToolCard card = new ToolCard(callId, tool, input,
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
}
