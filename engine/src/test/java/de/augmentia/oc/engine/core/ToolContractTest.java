package de.augmentia.oc.engine.core;
import de.augmentia.oc.engine.core.contract.provider.ModelContent;
import de.augmentia.oc.engine.core.contract.provider.Modality;
import de.augmentia.oc.engine.core.contract.tool.ToolCall;
import de.augmentia.oc.engine.core.contract.tool.ToolContext;
import de.augmentia.oc.engine.core.contract.tool.ToolDefinition;
import de.augmentia.oc.engine.core.contract.tool.ToolExecutor;
import de.augmentia.oc.engine.core.contract.tool.ToolFailure;
import de.augmentia.oc.engine.core.contract.tool.ToolGate;
import de.augmentia.oc.engine.core.contract.tool.ToolOptions;
import de.augmentia.oc.engine.core.contract.tool.ToolOutcome;
import de.augmentia.oc.engine.core.contract.tool.ToolResult;
import de.augmentia.oc.engine.core.contract.tool.ToolSnapshot;
import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.json.Schema;
import de.augmentia.oc.engine.core.model.*;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolContractTest {

    @Test
    void toolCallKeepsUncheckedInput() {
        JsonValue input = JsonValue.of(Map.of("path", JsonValue.of("a.txt")));
        ToolCall call = new ToolCall(CallId.create(), new ToolName("read"), input);
        assertThat(call.input()).isEqualTo(input);
        assertThatThrownBy(() -> new ToolCall(null, new ToolName("read"), input))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ToolCall(CallId.create(), null, input))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void toolContextRequiresAllIds() {
        ToolContext context = new ToolContext(SessionId.create(), AgentId.create(), MessageId.create(), CallId.create());
        assertThat(context.callId()).isNotNull();
        assertThatThrownBy(() -> new ToolContext(null, AgentId.create(), MessageId.create(), CallId.create()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ToolContext(SessionId.create(), AgentId.create(), null, CallId.create()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ToolContext(SessionId.create(), AgentId.create(), MessageId.create(), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void snapshotResolvesNamesInsideTheSnapshot() {
        ToolDefinition definition = definition(new ToolName("read"));
        ToolSnapshot snapshot = new ToolSnapshot(Map.of(new ToolName("read"), definition));

        assertThat(snapshot.list()).containsExactly(definition);
        assertThat(snapshot.require(new ToolName("read"))).isEqualTo(definition);
        assertThatThrownBy(() -> snapshot.require(new ToolName("write")))
                .isInstanceOf(ToolFailure.class)
                .hasMessageContaining("not available");
        assertThat(ToolSnapshot.empty().definitions()).isEmpty();

        ToolContext context = new ToolContext(SessionId.create(), AgentId.create(), MessageId.create(), CallId.create());
        ToolOutcome outcome = snapshot.execute(new ToolCall(CallId.create(), new ToolName("read"),
                JsonValue.ofNull()), context);
        assertThat(outcome).isInstanceOf(ToolOutcome.Ok.class);
        assertThatThrownBy(() -> snapshot.execute(null, context))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void toolGateDecidesVisibility() {
        ToolGate gate = (definition, rules) -> rules.stream().noneMatch(rule -> rule.effect() == Effect.DENY);
        ToolDefinition definition = definition(new ToolName("read"));
        assertThat(gate.visible(definition, List.of())).isTrue();
        assertThat(gate.visible(definition, List.of(new Rule("read", "*", Effect.DENY)))).isFalse();
    }

    @Test
    void toolOptionsDefaults() {
        ToolOptions options = ToolOptions.defaults();
        assertThat(options.namespace().isEmpty()).isTrue();
        assertThat(options.permission().isEmpty()).isTrue();
        assertThat(options.codemode()).isTrue();
        assertThat(options.pinned()).isFalse();
    }

    @Test
    void modelContentVariants() {
        ModelContent.Text text = new ModelContent.Text("hello");
        assertThat(text.text()).isEqualTo("hello");

        ModelContent.Media media = new ModelContent.Media(Modality.IMAGE, new byte[]{1, 2, 3}, Option.some("a.png"));
        assertThat(media.modality()).isEqualTo(Modality.IMAGE);
        assertThat(media.data()).containsExactly(1, 2, 3);
        assertThat(media.name().orElse("")).isEqualTo("a.png");
        assertThatThrownBy(() -> new ModelContent.Media(null, new byte[0], Option.none()))
                .isInstanceOf(IllegalArgumentException.class);

        ModelContent.ToolCall call = new ModelContent.ToolCall(CallId.create(), new ToolName("read"), JsonValue.ofNull());
        assertThat(call.name().value()).isEqualTo("read");
        assertThatThrownBy(() -> new ModelContent.ToolCall(null, new ToolName("read"), JsonValue.ofNull()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void retryStateAndSnapshotRange() {
        RetryState retry = new RetryState(1, null, new SessionError.Unknown("x", "y"));
        assertThat(retry.at()).isEqualTo(java.time.Instant.EPOCH);
        assertThatThrownBy(() -> new RetryState(-1, null, new SessionError.Unknown("x", "y")))
                .isInstanceOf(IllegalArgumentException.class);

        SnapshotRange range = new SnapshotRange("a", "b", List.of("f"));
        assertThat(range.files()).containsExactly("f");
        assertThatThrownBy(() -> new SnapshotRange(null, "b", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void attachmentAndShellInfo() {
        Attachment attachment = new Attachment("a.png", "image/png");
        assertThat(attachment.mime()).isEqualTo("image/png");
        assertThatThrownBy(() -> new Attachment(" ", "image/png"))
                .isInstanceOf(IllegalArgumentException.class);

        ShellInfo shell = new ShellInfo("s1", "ls", "done", Option.some(0));
        assertThat(shell.exit().orElse(-1)).isZero();
        assertThatThrownBy(() -> new ShellInfo(" ", "ls", "done", Option.none()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void locationRequiresDirectoryAndProject() {
        Location location = new Location(java.nio.file.Path.of("/tmp"), ProjectId.create(), Option.none());
        assertThat(location.workspaceId().isEmpty()).isTrue();
        assertThatThrownBy(() -> new Location(null, ProjectId.create(), Option.none()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Location(java.nio.file.Path.of("/tmp"), null, Option.none()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void toolFailureIsModelFacing() {
        ToolFailure failure = new ToolFailure("broken");
        assertThat(failure).hasMessage("broken");
        assertThat(new ToolFailure("broken", new IllegalStateException())).hasCauseInstanceOf(IllegalStateException.class);
    }

    private static ToolDefinition definition(ToolName name) {
        ToolExecutor executor = (input, context) -> new ToolOutcome.Ok(ToolResult.of("ok"));
        return new ToolDefinition(name, "description", Schema.obj(Map.of()), Option.none(), executor, null);
    }
}
